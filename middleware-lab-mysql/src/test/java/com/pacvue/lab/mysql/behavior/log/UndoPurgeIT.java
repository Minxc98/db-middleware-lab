package com.pacvue.lab.mysql.behavior.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 06 §3 - undo log: rollback on one side, the MVCC version chain on the other, and the
 * purge thread that can only clean up what no ReadView still needs.
 *
 * <p>Which is the mechanism behind "long transactions are harmful". Not a lock - a long
 * transaction that only ever reads still pins every version created since it started.
 */
class UndoPurgeIT extends AbstractBehaviorIT {

    private static final long ROW = 1L;

    @Override
    protected String table() {
        return "lab_log_undo";
    }

    @Override
    protected void createTable() {
        jdbc.execute("CREATE TABLE " + table()
                + " (id INT PRIMARY KEY, v VARCHAR(64)) ENGINE=InnoDB");
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO " + table() + " VALUES (1, 'v0')");
    }

    @Test
    @DisplayName("undo is what a rollback replays - and the server counts the rows it will undo")
    void undoBacksTheRollback() {
        Session session = session("writer");
        session.begin();
        session.execute("UPDATE " + table() + " SET v = 'changed' WHERE id = ?", ROW);

        Long modified = jdbc.queryForObject("""
                SELECT trx_rows_modified FROM information_schema.innodb_trx
                WHERE trx_mysql_thread_id = ?
                """, Long.class, session.connectionId());
        assertThat(modified)
                .as("the open transaction's outstanding undo work")
                .isEqualTo(1L);

        session.rollback();

        assertThat(jdbc.queryForObject(
                "SELECT v FROM " + table() + " WHERE id = ?", String.class, ROW))
                .isEqualTo("v0");
    }

    @Test
    @DisplayName("an old ReadView walks the version chain rather than seeing the current row")
    void anOldReadViewFollowsTheChain() {
        Session reader = session("reader");
        reader.beginWithSnapshot();
        assertThat(currentValueSeenBy(reader)).isEqualTo("v0");

        for (int i = 1; i <= 50; i++) {
            jdbc.update("UPDATE " + table() + " SET v = ? WHERE id = ?", "v" + i, ROW);
        }

        // Fifty committed versions later, the reader still gets the first one. Every one of
        // those reads walks back down 50 undo records to find it - which is the other cost of a
        // long-running snapshot, and it gets worse the longer it runs.
        assertThat(currentValueSeenBy(reader)).isEqualTo("v0");
        assertThat(jdbc.queryForObject(
                "SELECT v FROM " + table() + " WHERE id = ?", String.class, ROW)).isEqualTo("v50");

        reader.commit();
        assertThat(currentValueSeenBy(reader)).isEqualTo("v50");
    }

    @Test
    @DisplayName("an open transaction holds up purge; committing lets the history drain")
    void openTransactionHoldsUpPurge() {
        Session reader = session("reader");
        reader.beginWithSnapshot();
        reader.query("SELECT * FROM " + table());

        long before = historyListLength();
        for (int i = 1; i <= 300; i++) {
            jdbc.update("UPDATE " + table() + " SET v = ? WHERE id = ?", "v" + i, ROW);
        }
        long peak = historyListLength();

        // Every one of those updates left an undo record that purge is not allowed to discard,
        // because the reader's ReadView might still need it. The undo tablespace grows and the
        // version chains get longer; on a real system this is how a forgotten session turns
        // into disk pressure and slow reads with no lock waits anywhere.
        assertThat(peak)
                .as("history list length went %s -> %s", before, peak)
                .isGreaterThan(before);

        reader.commit();

        // With no ReadView left to need them, the purge thread can finally collect. Two things
        // make that slower than it sounds. It runs on its own schedule, so it has to be waited
        // for; and on a completely idle server it has little reason to wake up at all, so the
        // history list can sit at its peak for a long time. A trickle of writes is what gets it
        // moving - which also means a system that goes quiet right after a long transaction can
        // keep the undo it no longer needs for a surprisingly long time.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    jdbc.update("UPDATE " + table() + " SET v = 'nudge' WHERE id = ?", ROW);
                    assertThat(historyListLength()).isLessThan(peak);
                });
    }

    @Test
    @DisplayName("the history list length is the number to watch for this")
    void historyListLengthIsTheMetric() {
        // Enabled by default, unlike most of INNODB_METRICS - because this is the one people
        // actually need. It also appears in SHOW ENGINE INNODB STATUS as "History list length".
        assertThat(jdbc.queryForObject("""
                SELECT STATUS FROM information_schema.INNODB_METRICS
                WHERE NAME = 'trx_rseg_history_len'
                """, String.class)).isEqualTo("enabled");

        assertThat(locks.innodbStatus()).contains("History list length");
        assertThat(historyListLength()).isNotNegative();
    }

    private long historyListLength() {
        Long value = jdbc.queryForObject("""
                SELECT COUNT FROM information_schema.INNODB_METRICS
                WHERE NAME = 'trx_rseg_history_len'
                """, Long.class);
        return value == null ? -1 : value;
    }

    private String currentValueSeenBy(Session session) {
        return session.queryValue("SELECT v FROM " + table() + " WHERE id = ?", String.class, ROW);
    }
}
