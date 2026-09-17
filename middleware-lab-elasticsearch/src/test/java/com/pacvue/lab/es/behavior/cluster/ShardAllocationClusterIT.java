package com.pacvue.lab.es.behavior.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.json.JsonData;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractClusterIT;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

/**
 * 05-分片与路由 §5 / 06-集群与高可用 §3 - shard placement on a real cluster.
 *
 * <p>These are the parts of the notes a single node cannot show: that a replica is never
 * co-located with its primary, that allocation filtering is what hot/warm tiering is built
 * on, and that excluding a node makes its shards relocate rather than disappear.
 */
class ShardAllocationClusterIT extends AbstractClusterIT {

    private static final String TIER_INDEX = "it-cluster-tiering";

    @Override
    protected String indexName() {
        return "it-cluster-allocation";
    }

    @BeforeEach
    void createIndex() throws IOException {
        dropIndexIfPresent();
        client.indices().create(c -> c.index(indexName())
                .settings(s -> s.numberOfShards("2").numberOfReplicas("1")));
        waitForStatus(HealthStatus.Green);
    }

    @AfterEach
    void clearAllocationRules() throws IOException {
        // Transient cluster settings outlive the test otherwise, and would quietly break
        // every later test by pinning shards to one node.
        // JsonData.of(null) throws; an empty value is how you clear a filter.
        client.cluster().putSettings(s -> s.transient_(Map.of(
                "cluster.routing.allocation.exclude._name", JsonData.of(""))));
        dropIndexIfPresent();
        var tierOps = operations.indexOps(IndexCoordinates.of(TIER_INDEX));
        if (tierOps.exists()) {
            tierOps.delete();
        }
    }

    private void waitForStatus(HealthStatus expected) {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> client.cluster().health(h -> h.index(indexName())).status() == expected);
    }

    /** shard number -> the nodes holding a copy of it. */
    private Map<String, Set<String>> nodesByShard() throws IOException {
        var shards = client.cat().shards(s -> s.index(indexName()));
        return shards.valueBody().stream()
                .filter(r -> r.node() != null && !r.node().isBlank())
                .collect(Collectors.groupingBy(r -> r.shard(),
                        Collectors.mapping(r -> r.node(), Collectors.toSet())));
    }

    private Set<String> nodesHoldingShards() throws IOException {
        return nodesHoldingShardsOf(indexName());
    }

    private Set<String> nodesHoldingShardsOf(String target) throws IOException {
        return client.cat().shards(s -> s.index(target)).valueBody().stream()
                .map(r -> r.node())
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a replica is never placed on the same node as its primary")
    void replicaNeverSharesANodeWithItsPrimary() throws IOException {
        Map<String, Set<String>> byShard = nodesByShard();

        assertThat(byShard).hasSize(2);
        assertThat(byShard.values()).allSatisfy(nodes -> assertThat(nodes)
                .as("primary and replica of one shard must live on different nodes")
                .hasSize(2));
    }

    @Test
    @DisplayName("green means every primary and replica is assigned; the cluster has 3 nodes")
    void greenOnAThreeNodeCluster() throws IOException {
        var health = client.cluster().health(h -> h.index(indexName()));

        assertThat(health.status()).isEqualTo(HealthStatus.Green);
        assertThat(health.numberOfNodes()).isGreaterThanOrEqualTo(3);
        assertThat(health.activePrimaryShards()).isEqualTo(2);
        assertThat(health.activeShards()).isEqualTo(4); // 2 primaries + 2 replicas
        assertThat(health.unassignedShards()).isZero();
    }

    @Test
    @DisplayName("asking for more replicas than nodes leaves the extra copies unassigned")
    void tooManyReplicasCannotBePlaced() throws IOException {
        // 3 nodes can hold a primary plus 2 replicas; a third replica has nowhere to go.
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("3")));

        waitForStatus(HealthStatus.Yellow);

        var health = client.cluster().health(h -> h.index(indexName()));
        assertThat(health.unassignedShards()).isEqualTo(2); // one per shard
        assertThat(health.activeShards()).isEqualTo(6);     // 2 primaries + 2x2 replicas
    }

    @Test
    @DisplayName("allocation filtering pins an index to tagged nodes - the basis of hot/warm")
    void allocationFilteringPinsShardsToTaggedNodes() throws IOException {
        // Its own index, with no replicas: see the next test for why that matters here.
        client.indices().create(c -> c.index(TIER_INDEX)
                .settings(s -> s.numberOfShards("2").numberOfReplicas("0")));

        // es01=hot, es02=warm, es03=cold (node.attr.tier in docker-compose.cluster.yml)
        client.indices().putSettings(s -> s.index(TIER_INDEX)
                .settings(t -> t.otherSettings("index.routing.allocation.require.tier",
                        JsonData.of("hot"))));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> nodesHoldingShardsOf(TIER_INDEX).equals(Set.of("es01")));

        // Moving the index to the warm tier relocates every shard to another node. This is
        // exactly the mechanism ILM uses to age data from hot to warm to cold hardware.
        client.indices().putSettings(s -> s.index(TIER_INDEX)
                .settings(t -> t.otherSettings("index.routing.allocation.require.tier",
                        JsonData.of("warm"))));

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> nodesHoldingShardsOf(TIER_INDEX).equals(Set.of("es02")));
    }

    @Test
    @DisplayName("filtering never overrides availability: ES leaves a shard on a 'wrong' node")
    void filteringDoesNotOverrideTheSameNodeRule() throws IOException {
        // This index has 1 replica, so each shard needs two distinct nodes. Pinning it to a
        // single node is unsatisfiable.
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.otherSettings("index.routing.allocation.require._name",
                        JsonData.of("es01"))));

        // Give the cluster time to do whatever it is going to do.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(30))
                .until(() -> client.cluster().health(h -> h.index(indexName())).status() == HealthStatus.Green);

        // Shards stayed put on nodes that do not match the filter, rather than becoming
        // unassigned. Allocation explain says it outright:
        //   can_remain_on_current_node: no
        //   "This shard may not remain on its current node, but Elasticsearch isn't allowed
        //    to move it to another node."
        // Availability wins over the filter - worth knowing before blaming a "stuck" filter.
        assertThat(nodesHoldingShards()).isNotEqualTo(Set.of("es01"));
        assertThat(client.cluster().health(h -> h.index(indexName())).unassignedShards()).isZero();
    }

    @Test
    @DisplayName("excluding a node relocates its shards instead of losing them")
    void excludingANodeRelocatesItsShards() throws IOException {
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(List.of(ArticleDoc.of("1", "one", "news"), ArticleDoc.of("2", "two", "news")),
                        index());

        String victim = nodesHoldingShards().iterator().next();

        // This is what a graceful node decommission looks like.
        client.cluster().putSettings(s -> s.transient_(Map.of(
                "cluster.routing.allocation.exclude._name", JsonData.of(victim))));

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                .until(() -> !nodesHoldingShards().contains(victim));

        // Nothing was lost and the cluster is whole again: 2 remaining nodes still hold
        // a full set of primaries and replicas.
        waitForStatus(HealthStatus.Green);
        assertThat(nodesByShard().values()).allSatisfy(nodes -> assertThat(nodes).hasSize(2));
        assertThat(operations.get("1", ArticleDoc.class, index())).isNotNull();
    }
}
