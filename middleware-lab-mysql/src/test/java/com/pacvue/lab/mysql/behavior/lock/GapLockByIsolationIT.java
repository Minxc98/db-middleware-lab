package com.pacvue.lab.mysql.behavior.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.LockInspector;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 05 §4 - gap locks exist to stop phantoms, phantoms are a REPEATABLE READ concern, and so
 * gap locks all but disappear under READ COMMITTED.
 *
 * <p>This is the concrete content of "RC has higher concurrency": not a smaller lock, a missing
 * one.
 */
class GapLockByIsolationIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_lock_gap";
    }

    @Override
    protected void createTable() {
        createLockTable();
    }

    @BeforeEach
    void seed() {
        insertLockRows(5, 10, 15, 20);
    }

    @Test
    @DisplayName("REPEATABLE READ locks the gaps a range passes through")
    void repeatableReadTakesGapLocks() {
        Session session = session("rr");
        session.begin();
        session.query("SELECT * FROM " + table() + " FORCE INDEX (PRIMARY)"
                + " WHERE id >= 10 AND id < 16 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        assertThat(rowLocks)
                .as("locks were: %s", rowLocks)
                .anySatisfy(lock -> assertThat(lock.isNextKey()).isTrue());
    }

    @Test
    @DisplayName("READ COMMITTED takes record locks only - no gaps anywhere")
    void readCommittedTakesNoGapLocks() {
        Session session = session("rc").isolation("READ COMMITTED");
        session.begin();
        session.query("SELECT * FROM " + table() + " FORCE INDEX (PRIMARY)"
                + " WHERE id >= 10 AND id < 16 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // Only the two matching rows, and each lock covers the row and nothing around it.
        assertThat(rowLocks).allSatisfy(lock -> assertThat(lock.isRecordOnly()).isTrue());
        assertThat(rowLocks).extracting(LockInspector.Lock::data)
                .containsExactlyInAnyOrder("10", "15");
    }

    @Test
    @DisplayName("so under RC the insert that RR blocks goes straight through")
    void readCommittedLetsTheInsertThrough() {
        Session rc = session("rc").isolation("READ COMMITTED");
        Session writer = session("writer");

        rc.begin();
        rc.query("SELECT * FROM " + table() + " FORCE INDEX (PRIMARY)"
                + " WHERE id >= 10 AND id < 16 FOR UPDATE");

        // id 12 falls in the gap (10,15) - held under RR, free under RC.
        writer.execute("INSERT INTO " + table() + " (id, order_no, user_id, remark)"
                + " VALUES (12, 'NO-12', 120, 'row-12')");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Long.class)).isEqualTo(5);
        rc.rollback();
    }

    @Test
    @DisplayName("an INSERT waiting on a gap shows up as an insert-intention lock")
    void blockedInsertIsAnInsertIntentionLock() {
        Session holder = session("holder");
        Session inserter = session("inserter");

        holder.begin();
        holder.query("SELECT * FROM " + table() + " WHERE id = 12 FOR UPDATE");   // gap (10,15)

        Future<Integer> blocked = inserter.submit("INSERT INTO " + table()
                + " (id, order_no, user_id, remark) VALUES (12, 'NO-12', 120, 'row-12')");
        Session.assertStillBlocked(blocked, Duration.ofMillis(800), "insert into a locked gap");

        // Gap locks do not conflict with each other - they conflict with *insertions*, and this
        // is the request that represents one. It is the only lock mode that exists purely to be
        // blocked by a gap.
        List<LockInspector.Lock> waiting = locks.rowLocksOf(inserter, table());
        assertThat(waiting)
                .as("waiting locks were: %s", waiting)
                .anySatisfy(lock -> {
                    assertThat(lock.mode()).contains("INSERT_INTENTION");
                    assertThat(lock.status()).isEqualTo("WAITING");
                });
        assertThat(locks.isWaiting(inserter)).isTrue();

        holder.rollback();
        Session.awaitCompletion(blocked, Duration.ofSeconds(5), "the blocked insert");
    }

    @Test
    @DisplayName("two gap locks over the same gap coexist - they only block inserts")
    void gapLocksDoNotConflictWithEachOther() {
        Session first = session("first");
        Session second = session("second");

        first.begin();
        first.query("SELECT * FROM " + table() + " WHERE id = 12 FOR UPDATE");

        second.begin();
        // Same gap, also exclusive, and it is granted immediately.
        second.query("SELECT * FROM " + table() + " WHERE id = 13 FOR UPDATE");

        assertThat(locks.rowLocksOf(first, table()))
                .allSatisfy(lock -> assertThat(lock.status()).isEqualTo("GRANTED"));
        assertThat(locks.rowLocksOf(second, table()))
                .allSatisfy(lock -> assertThat(lock.status()).isEqualTo("GRANTED"));

        // Which is also how two sessions can deadlock by both holding the gap and both then
        // trying to insert into it - see DeadlockIT.
        first.rollback();
        second.rollback();
    }
}
