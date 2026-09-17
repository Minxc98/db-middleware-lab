package com.pacvue.lab.mysql.behavior.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pacvue.lab.mysql.support.AbstractReplicationIT;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 07 §4/§5 - replication lag, and the "write then read and it is not there" problem that
 * read/write splitting inherits.
 *
 * <p>Lag is produced here by stopping the apply thread. On a real system the cause is different -
 * a slow replica, a huge transaction, single-threaded apply - but the consequence for the
 * application is exactly this: the row is committed on the source and not on the replica.
 */
class ReplicationLagIT extends AbstractReplicationIT {

    @Override
    protected String table() {
        return "lab_repl_lag";
    }

    @Test
    @DisplayName("with the apply thread stopped the replica falls behind - and reports NULL lag")
    void stoppingTheApplyThreadCreatesABacklog() {
        replica.execute("STOP REPLICA SQL_THREAD");
        try {
            source.execute("INSERT INTO " + table() + " VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            awaitRetrieved();

            assertThat(rowsOnSource()).isEqualTo(3);
            // The IO thread is still pulling: the transactions are in the relay log on the
            // replica's own disk, just not applied. Nothing has been lost - it is late.
            assertThat(replicaStatus("Replica_IO_Running")).isEqualTo("Yes");
            assertThat(replicaStatus("Replica_SQL_Running")).isEqualTo("No");
            assertThat(rowsOnReplica()).isZero();

            // And here is the trap. Seconds_Behind_Source is computed by the apply thread from
            // the timestamp of the event it is working on. With that thread stopped there is
            // nothing to compute it from, so it reports NULL - not a large number.
            // A monitor that alerts on "lag > 30s" will therefore say nothing at all about a
            // replica that has stopped applying entirely, which is the worse failure.
            assertThat(replicaStatus().get("Seconds_Behind_Source"))
                    .as("lag while the applier is stopped")
                    .isNull();

            // The signal that does work: what the replica has received minus what it has
            // executed. Non-empty means there is a backlog, whatever the thread is doing.
            assertThat(backlog())
                    .as("received but not yet executed")
                    .isNotEmpty();
        } finally {
            replica.execute("START REPLICA SQL_THREAD");
        }

        // Once it restarts, the backlog drains and both measures agree again.
        awaitReplicated(Duration.ofSeconds(20));
        assertThat(rowsOnReplica()).isEqualTo(3);
        assertThat(backlog()).isEmpty();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(secondsBehind()).isZero());
    }

    @Test
    @DisplayName("read-after-write against a lagging replica returns nothing - unless you wait for the GTID")
    void readAfterWriteNeedsMoreThanHope() {
        replica.execute("STOP REPLICA SQL_THREAD");
        try {
            source.execute("INSERT INTO " + table() + " VALUES (1, 'just written')");
            String gtid = source.queryValue("SELECT @@global.gtid_executed", String.class);

            // The naive read/write split: write to the source, read from a replica. The row is
            // committed and durable, and the read still misses it.
            assertThat(replica.queryValue(
                    "SELECT v FROM " + table() + " WHERE id = 1", String.class)).isNull();

            // The mechanical fix, and the reason GTIDs are worth having: ask the replica to wait
            // until it has executed the exact transaction you just committed. 1 means it timed
            // out - which it does, because the apply thread is stopped.
            assertThat(replica.queryValue(
                    "SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 1)", Integer.class, gtid))
                    .as("timed out waiting, because nothing is being applied")
                    .isEqualTo(1);

            replica.execute("START REPLICA SQL_THREAD");

            // Now the same call blocks for as long as it needs and returns 0 - and the read that
            // follows is guaranteed to see the write.
            assertThat(replica.queryValue(
                    "SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 20)", Integer.class, gtid)).isZero();
            assertThat(replica.queryValue(
                    "SELECT v FROM " + table() + " WHERE id = 1", String.class))
                    .isEqualTo("just written");
        } finally {
            if ("No".equals(replicaStatus("Replica_SQL_Running"))) {
                replica.execute("START REPLICA SQL_THREAD");
            }
        }
    }

    @Test
    @DisplayName("parallel apply is configured - the first answer to lag that is not 'buy a faster disk'")
    void parallelApplyIsOn() {
        // A single apply thread replaying what an arbitrary number of client connections wrote
        // is the usual reason a replica cannot keep up. LOGICAL_CLOCK lets the replica run in
        // parallel the transactions that committed together on the source, and therefore could
        // not have conflicted.
        assertThat(replica.queryValue("SELECT @@replica_parallel_workers", Integer.class))
                .isGreaterThan(1);
        assertThat(replica.queryValue("SELECT @@replica_parallel_type", String.class))
                .isEqualTo("LOGICAL_CLOCK");
    }

    private long secondsBehind() {
        Object value = replicaStatus().get("Seconds_Behind_Source");
        return value == null ? -1 : ((Number) value).longValue();
    }

    /** GTIDs the replica has pulled into its relay log but not applied yet. */
    private String backlog() {
        Map<String, Object> status = replicaStatus();
        String retrieved = String.valueOf(status.get("Retrieved_Gtid_Set"));
        String executed = String.valueOf(status.get("Executed_Gtid_Set"));
        String difference = replica.queryValue(
                "SELECT GTID_SUBTRACT(?, ?)", String.class, retrieved, executed);
        return difference == null ? "" : difference.trim();
    }

    /** Waits until the replica has pulled everything the source has, applied or not. */
    private void awaitRetrieved() {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(String.valueOf(
                        replicaStatus().get("Retrieved_Gtid_Set"))).isNotBlank());
    }
}
