package com.pacvue.lab.mysql.behavior.replication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pacvue.lab.mysql.support.AbstractReplicationIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 07 §1/§3 - the replication link itself: three threads, a relay log, and GTIDs instead of
 * file-and-position bookkeeping.
 */
class ReplicationTopologyIT extends AbstractReplicationIT {

    @Override
    protected String table() {
        return "lab_repl_topology";
    }

    @Test
    @DisplayName("both replication threads are running, and the replica knows its source")
    void bothThreadsRun() {
        // Replica_IO_Running is the thread pulling the binlog into the local relay log;
        // Replica_SQL_Running is the one replaying the relay log. They fail independently:
        // a network problem stops the first, an apply error stops the second.
        assertThat(replicaStatus("Replica_IO_Running")).isEqualTo("Yes");
        assertThat(replicaStatus("Replica_SQL_Running")).isEqualTo("Yes");
        assertThat(replicaStatus("Source_Host")).isEqualTo("source");
        assertThat(replicaStatus("Last_Error")).isEmpty();
    }

    @Test
    @DisplayName("a write on the source turns up on the replica")
    void writesReachTheReplica() {
        source.execute("INSERT INTO " + table() + " VALUES (1, 'written on the source')");

        awaitReplicated();

        assertThat(rowsOnReplica()).isEqualTo(1);
        assertThat(replica.queryValue("SELECT v FROM " + table() + " WHERE id = 1", String.class))
                .isEqualTo("written on the source");
    }

    @Test
    @DisplayName("the replica refuses writes, even from root")
    void replicaIsReadOnly() {
        assertThat(replica.queryValue("SELECT @@super_read_only", Integer.class)).isEqualTo(1);

        // read_only alone exempts accounts with SUPER - which root has, so it would let exactly
        // the account most likely to be used for a manual "quick fix" write to the replica and
        // silently fork the two servers.
        assertThatThrownBy(() -> replica.execute("INSERT INTO " + table() + " VALUES (99, 'x')"))
                .hasStackTraceContaining("--super-read-only");

        // The replication applier is not affected by it.
        source.execute("INSERT INTO " + table() + " VALUES (1, 'a')");
        awaitReplicated();
        assertThat(rowsOnReplica()).isEqualTo(1);
    }

    @Test
    @DisplayName("GTIDs: the replica records which transactions it has executed, not a file offset")
    void gtidsTrackProgress() {
        assertThat(replicaStatus("Auto_Position")).isEqualTo("1");

        String sourceUuid = source.queryValue("SELECT @@server_uuid", String.class);
        source.execute("INSERT INTO " + table() + " VALUES (1, 'a')");
        awaitReplicated();

        String executed = replica.queryValue("SELECT @@global.gtid_executed", String.class);

        // The replica's executed set contains the source's server_uuid: these transactions came
        // from there, and it knows it. On failover the new source can work out what this replica
        // is missing by set subtraction, instead of somebody reading a file name and an offset
        // off a screen under pressure.
        assertThat(executed).contains(sourceUuid);

        // Both sides agree on what has been executed.
        assertThat(replica.queryValue(
                "SELECT GTID_SUBSET(?, @@global.gtid_executed)", Integer.class,
                source.queryValue("SELECT @@global.gtid_executed", String.class)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the replica keeps its own binlog, so it could be promoted")
    void replicaLogsWhatItApplies() {
        assertThat(replica.queryValue("SELECT @@log_replica_updates", Integer.class)).isEqualTo(1);

        source.execute("INSERT INTO " + table() + " VALUES (1, 'a')");
        awaitReplicated();

        // Without log_replica_updates a replica applies changes without writing its own binlog -
        // it can serve reads, but it cannot become a source and cannot feed another replica
        // without a restart. In a failover plan that restart is the outage.
        assertThat(replica.query("SHOW BINARY LOGS")).isNotEmpty();
    }
}
