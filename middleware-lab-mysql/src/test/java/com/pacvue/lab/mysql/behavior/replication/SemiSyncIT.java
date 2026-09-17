package com.pacvue.lab.mysql.behavior.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pacvue.lab.mysql.support.AbstractReplicationIT;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 07 §2 - semi-synchronous replication: the source waits for one replica to acknowledge
 * that it has the transaction before telling the client the commit succeeded.
 *
 * <p>What that buys is narrow and worth stating precisely. It does not make the replica current,
 * and it is not a consensus protocol. It means a committed transaction exists in at least two
 * places, so losing the source does not lose it.
 */
class SemiSyncIT extends AbstractReplicationIT {

    @Override
    protected String table() {
        return "lab_repl_semisync";
    }

    @Test
    @DisplayName("semi-sync is active on both ends")
    void semiSyncIsOn() {
        assertThat(statusOf(source, "Rpl_semi_sync_source_status")).isEqualTo("ON");
        assertThat(statusOf(replica, "Rpl_semi_sync_replica_status")).isEqualTo("ON");
        assertThat(statusOf(source, "Rpl_semi_sync_source_clients"))
                .as("one replica acknowledging")
                .isEqualTo("1");
    }

    @Test
    @DisplayName("each commit is acknowledged by the replica before it returns")
    void commitsAreAcknowledged() {
        long acknowledgedBefore = counterOf("Rpl_semi_sync_source_yes_tx");

        source.execute("INSERT INTO " + table() + " VALUES (1, 'a')");

        // The counter moves as part of the commit, not afterwards: by the time the INSERT
        // returned, the replica had the transaction in its relay log.
        assertThat(counterOf("Rpl_semi_sync_source_yes_tx"))
                .as("acknowledged transactions")
                .isGreaterThan(acknowledgedBefore);

        // Acknowledged is not the same as applied. The replica confirms it has written the
        // transaction to its relay log; replaying it happens on the replica's own schedule, so
        // reading from the replica immediately can still miss the row (see ReplicationLagIT).
        assertThat(counterOf("Rpl_semi_sync_source_no_tx"))
                .as("transactions that fell back to asynchronous")
                .isNotNegative();
    }

    @Test
    @DisplayName("with no replica to acknowledge, the source waits and then degrades to async")
    void sourceFallsBackToAsyncWhenNobodyAcknowledges() {
        long fellBackBefore = counterOf("Rpl_semi_sync_source_no_tx");

        // Disconnect the acknowledging side. Semi-sync is an availability trade, and this is the
        // moment it has to be made: block writes, or accept the risk?
        replica.execute("STOP REPLICA IO_THREAD");
        try {
            // Rpl_semi_sync_source_clients is deliberately not waited on here: the source keeps
            // the dump thread for a disconnected replica around for a while, so the count stays
            // at 1 long after the replica stopped acknowledging anything. The count answers
            // "how many replicas are attached", not "how many will acknowledge my next commit".
            long start = System.nanoTime();
            source.execute("INSERT INTO " + table() + " VALUES (1, 'a')");
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            // MySQL chooses availability: it waits rpl_semi_sync_source_timeout (3s here, 10s by
            // default), gives up, switches the whole server to asynchronous and commits. Writes
            // keep flowing, and the guarantee is gone until a replica comes back - which is the
            // part worth knowing, because nothing in the application notices.
            assertThat(waited).isGreaterThan(Duration.ofSeconds(2));
            assertThat(counterOf("Rpl_semi_sync_source_no_tx"))
                    .as("this commit was not acknowledged by anyone")
                    .isGreaterThan(fellBackBefore);
            assertThat(statusOf(source, "Rpl_semi_sync_source_status"))
                    .as("semi-sync has switched itself off")
                    .isEqualTo("OFF");
        } finally {
            replica.execute("START REPLICA IO_THREAD");
        }

        // When a replica returns, the source switches semi-sync back on by itself.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(statusOf(source, "Rpl_semi_sync_source_status"))
                        .isEqualTo("ON"));
        awaitReplicated(Duration.ofSeconds(20));
        assertThat(rowsOnReplica()).isEqualTo(1);
    }

    private String statusOf(com.pacvue.lab.mysql.support.Session session, String name) {
        return session.query("SHOW GLOBAL STATUS LIKE ?", name).stream()
                .findFirst()
                .map(row -> String.valueOf(row.get("Value")))
                .orElse(null);
    }

    private long counterOf(String name) {
        String value = statusOf(source, name);
        return value == null ? -1 : Long.parseLong(value);
    }
}
