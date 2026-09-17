package com.pacvue.lab.mysql.behavior.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.LockInspector;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 01 §4 - InnoDB vs MyISAM, the table everyone recites. Each row of it is checked here
 * against the running server rather than taken on faith.
 */
class EngineComparisonIT extends AbstractBehaviorIT {

    private static final String MYISAM_TABLE = "lab_cmp_myisam";

    @Override
    protected String table() {
        return "lab_cmp_innodb";
    }

    @Override
    protected void createTable() {
        jdbc.execute("CREATE TABLE " + table()
                + " (id INT PRIMARY KEY, note VARCHAR(50)) ENGINE=InnoDB");
        jdbc.execute("DROP TABLE IF EXISTS " + MYISAM_TABLE);
        jdbc.execute("CREATE TABLE " + MYISAM_TABLE
                + " (id INT PRIMARY KEY, note VARCHAR(50)) ENGINE=MyISAM");
    }

    @Test
    @DisplayName("MyISAM ignores ROLLBACK; InnoDB honours it")
    void onlyInnodbRollsBack() {
        Session session = session("writer");

        session.begin();
        session.execute("INSERT INTO " + table() + " (id, note) VALUES (1, 'innodb')");
        session.rollback();

        // Separate transaction: with gtid_mode=ON the server refuses to put both writes in one
        // (see mixingEnginesInOneTransactionIsRejectedUnderGtid below).
        session.begin();
        session.execute("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (1, 'myisam')");
        session.rollback();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Long.class))
                .as("InnoDB row after ROLLBACK")
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + MYISAM_TABLE, Long.class))
                .as("MyISAM row after ROLLBACK - the engine never knew a transaction was open")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("with GTIDs on, one transaction cannot touch both a transactional and a non-transactional table")
    void mixingEnginesInOneTransactionIsRejectedUnderGtid() {
        Session session = session("mixer");
        session.begin();
        session.execute("INSERT INTO " + table() + " (id, note) VALUES (1, 'innodb')");

        // A GTID names one atomic unit of replication. A transaction that half rolls back (the
        // InnoDB part) and half does not (the MyISAM part) cannot be replayed atomically on a
        // replica, so the server refuses to create one. This is the practical reason a mixed
        // -engine schema and GTID replication do not go together.
        assertThatThrownBy(() ->
                session.execute("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (1, 'myisam')"))
                .hasStackTraceContaining("violates GTID consistency");

        session.rollback();
    }

    @Test
    @DisplayName("MyISAM has no row locks at all - SELECT FOR UPDATE takes nothing")
    void rowLocksAreAnInnodbFeature() {
        jdbc.update("INSERT INTO " + table() + " (id, note) VALUES (1, 'a'), (2, 'b')");
        jdbc.update("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (1, 'a'), (2, 'b')");

        Session session = session("locker");
        session.begin();
        session.query("SELECT * FROM " + table() + " WHERE id = 1 FOR UPDATE");
        session.query("SELECT * FROM " + MYISAM_TABLE + " WHERE id = 1 FOR UPDATE");

        List<LockInspector.Lock> innodbLocks = locks.rowLocksOf(session, table());
        List<LockInspector.Lock> myisamLocks = locks.rowLocksOf(session, MYISAM_TABLE);

        assertThat(innodbLocks)
                .as("InnoDB took a row lock: %s", innodbLocks)
                .isNotEmpty();
        // performance_schema.data_locks is fed by InnoDB. MyISAM has nothing to report
        // because its only granularity is the whole table.
        assertThat(myisamLocks)
                .as("MyISAM row locks")
                .isEmpty();

        session.rollback();
    }

    @Test
    @DisplayName("a MyISAM write blocks concurrent readers; an InnoDB write on another row does not")
    void lockGranularityDiffers() {
        jdbc.update("INSERT INTO " + table() + " (id, note) VALUES (1, 'a'), (2, 'b')");
        jdbc.update("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (1, 'a'), (2, 'b')");

        Session holder = session("holder");
        Session other = session("other");

        holder.begin();
        holder.execute("UPDATE " + table() + " SET note = 'x' WHERE id = 1");

        // Row lock on id=1 only, so id=2 is free.
        other.execute("UPDATE " + table() + " SET note = 'y' WHERE id = 2");
        assertThat(jdbc.queryForObject(
                "SELECT note FROM " + table() + " WHERE id = 2", String.class)).isEqualTo("y");
        holder.rollback();

        // MyISAM: an explicit table lock is the only kind it has, and it stops everything.
        holder.execute("LOCK TABLES " + MYISAM_TABLE + " WRITE");
        Future<Integer> blocked = other.submit("UPDATE " + MYISAM_TABLE + " SET note = 'y' WHERE id = 2");
        Session.assertStillBlocked(blocked, Duration.ofMillis(700),
                "update of a different MyISAM row while the table is write-locked");

        holder.execute("UNLOCK TABLES");
        Session.awaitCompletion(blocked, Duration.ofSeconds(5), "the blocked update");
    }

    @Test
    @DisplayName("MyISAM keeps an exact row count; InnoDB has to count")
    void rowCountIsMetadataForMyisamAndWorkForInnodb() {
        for (int i = 1; i <= 500; i++) {
            jdbc.update("INSERT INTO " + table() + " (id, note) VALUES (?, 'x')", i);
            jdbc.update("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (?, 'x')", i);
        }
        jdbc.execute("ANALYZE TABLE " + table());
        jdbc.execute("ANALYZE TABLE " + MYISAM_TABLE);

        Long myisamRows = jdbc.queryForObject(
                "SELECT TABLE_ROWS FROM information_schema.TABLES"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Long.class, MYISAM_TABLE);

        assertThat(myisamRows)
                .as("MyISAM stores the count in the table header, so metadata is exact")
                .isEqualTo(500L);

        // For InnoDB the same column is a sampled estimate. The suite does not assert a value
        // for it on purpose: asserting an estimate would be asserting the sampler's mood. What
        // it does assert is that COUNT(*) - which reads the index - is right.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Long.class))
                .isEqualTo(500L);
    }
}
