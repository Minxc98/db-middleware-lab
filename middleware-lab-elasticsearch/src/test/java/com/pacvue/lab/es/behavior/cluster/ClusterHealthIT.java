package com.pacvue.lab.es.behavior.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.HealthStatus;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 05-分片与路由 §6 / 06-集群与高可用 - health colours and unassigned shards.
 *
 * <p>green = every primary and replica assigned, yellow = a replica is missing (still
 * readable and writable), red = a primary is missing. On a single node any replica is
 * unassignable by definition, which makes yellow easy to reproduce and explain.
 */
class ClusterHealthIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-cluster-health";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0));
    }

    private HealthStatus healthOfThisIndex() throws IOException {
        return client.cluster().health(h -> h.index(indexName())).status();
    }

    @Test
    @DisplayName("no replicas on a single node: green")
    void withoutReplicasTheIndexIsGreen() throws IOException {
        assertThat(healthOfThisIndex()).isEqualTo(HealthStatus.Green);
    }

    @Test
    @DisplayName("asking for a replica on a single node: yellow, and still fully usable")
    void replicaOnSingleNodeMakesItYellow() throws IOException {
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("1")));

        assertThat(healthOfThisIndex()).isEqualTo(HealthStatus.Yellow);

        var health = client.cluster().health(h -> h.index(indexName()));
        assertThat(health.unassignedShards()).isEqualTo(1);
        assertThat(health.activePrimaryShards()).isEqualTo(1);

        // Yellow is a availability warning, not an outage: reads and writes keep working.
        operations.save(ArticleDoc.of("1", "written while yellow", "news"), index());
        refresh();
        assertThat(operations.get("1", ArticleDoc.class, index())).isNotNull();
    }

    @Test
    @DisplayName("the unassigned shard is the replica, and it is unassigned for a reason")
    void unassignedShardIsTheReplica() throws IOException {
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("1")));

        var shards = client.cat().shards(s -> s.index(indexName()));

        assertThat(shards.valueBody()).hasSize(2);
        assertThat(shards.valueBody()).filteredOn(r -> "p".equals(r.prirep()))
                .allSatisfy(r -> assertThat(r.state()).isEqualTo("STARTED"));
        assertThat(shards.valueBody()).filteredOn(r -> "r".equals(r.prirep()))
                .allSatisfy(r -> assertThat(r.state()).isEqualTo("UNASSIGNED"));

        // In production the next step is GET /_cluster/allocation/explain, which names the
        // decider that refused the shard. Note: the Java client 8.18.6 fails to decode that
        // endpoint's response ("Failed to decode response"), so reach for curl or Kibana
        // there rather than the typed client.
    }

    @Test
    @DisplayName("disk watermarks are cluster settings, and they gate allocation")
    void diskWatermarksAreVisible() throws IOException {
        var settings = client.cluster().getSettings(g -> g.includeDefaults(true).flatSettings(true));

        Map<String, ?> defaults = settings.defaults();
        assertThat(defaults.get("cluster.routing.allocation.disk.watermark.low")).isNotNull();
        assertThat(defaults.get("cluster.routing.allocation.disk.watermark.high")).isNotNull();
        assertThat(defaults.get("cluster.routing.allocation.disk.watermark.flood_stage")).isNotNull();
    }
}
