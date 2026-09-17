package com.pacvue.lab.mysql.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for tests that need the two-node topology:
 *
 * <pre>
 * cd middleware-lab-mysql
 * docker compose -f docker-compose.replication.yml up -d
 * </pre>
 *
 * <p>No Spring context here. The rest of the suite has one data source and asks questions of it;
 * these tests have two servers and the questions are about the difference between them.
 */
@RequiresReplicationTopology
public abstract class AbstractReplicationIT {

    protected Session source;
    protected Session replica;

    /** Table this test class owns. Created on the source; it reaches the replica on its own. */
    protected abstract String table();

    @BeforeEach
    void connectAndReset() {
        source = ReplicationCluster.source("source");
        replica = ReplicationCluster.replica("replica");

        // DDL goes to the source only - the replica is super_read_only, and a table created
        // directly on it would be a divergence, not a fixture.
        source.execute("DROP TABLE IF EXISTS " + table());
        source.execute("CREATE TABLE " + table()
                + " (id BIGINT NOT NULL PRIMARY KEY, v VARCHAR(64)) ENGINE=InnoDB");
        awaitReplicaHasTable();
    }

    @AfterEach
    void disconnect() {
        if (source != null) {
            source.close();
        }
        if (replica != null) {
            replica.close();
        }
    }

    /** {@code SHOW REPLICA STATUS} as a map - the whole health of the link in one row. */
    protected Map<String, Object> replicaStatus() {
        var rows = replica.query("SHOW REPLICA STATUS");
        assertThat(rows).as("SHOW REPLICA STATUS returned nothing - is this a replica?").hasSize(1);
        return rows.get(0);
    }

    protected String replicaStatus(String column) {
        Object value = replicaStatus().get(column);
        return value == null ? null : String.valueOf(value);
    }

    /** Waits until the replica has caught up with everything the source has committed. */
    protected void awaitReplicated(Duration timeout) {
        String gtidSet = source.queryValue("SELECT @@global.gtid_executed", String.class);
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(replica.queryValue(
                        "SELECT WAIT_FOR_EXECUTED_GTID_SET(?, 1)", Integer.class, gtidSet))
                        .as("replica has not caught up with %s", gtidSet)
                        .isZero());
    }

    protected void awaitReplicated() {
        awaitReplicated(Duration.ofSeconds(15));
    }

    protected long rowsOnReplica() {
        return replica.count("SELECT COUNT(*) FROM " + table());
    }

    protected long rowsOnSource() {
        return source.count("SELECT COUNT(*) FROM " + table());
    }

    private void awaitReplicaHasTable() {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(replica.count("""
                        SELECT COUNT(*) FROM information_schema.TABLES
                        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                        """, table())).isEqualTo(1));
    }
}
