package com.pacvue.lab.es.behavior.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.pacvue.lab.es.support.AbstractClusterIT;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 06-集群与高可用 §1,§2 / 面试题 Q5.1 - master election and quorum.
 *
 * <p>Since 7.x there is no {@code minimum_master_nodes} to get wrong: the cluster maintains a
 * <b>voting configuration</b> itself and derives the quorum from it. These tests read that
 * configuration out of the cluster state, and exercise the one operation you actually run in
 * production - excluding a node from the vote before decommissioning it.
 */
class MasterElectionClusterIT extends AbstractClusterIT {

    @Override
    protected String indexName() {
        return "it-cluster-election"; // unused, the base class needs a name
    }

    @BeforeEach
    void startFromAFullVotingConfiguration() throws IOException {
        // Removing an exclusion updates the cluster state asynchronously, so a test that
        // merely calls delete in @AfterEach can still hand the next test a shrunken
        // configuration. Wait for it to actually be back to full before asserting anything.
        client.cluster().deleteVotingConfigExclusions(e -> e.waitForRemoval(false));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> votingConfiguration().size() == masterEligibleNodeIds().size());
    }

    @AfterEach
    void clearVotingExclusions() throws IOException {
        // waitForRemoval defaults to true, which blocks until the excluded node actually
        // LEAVES the cluster. Here the node is still running, so the default would hang
        // until timeout and leave the exclusion in place for the next test.
        client.cluster().deleteVotingConfigExclusions(e -> e.waitForRemoval(false));
    }

    /** Node ids currently allowed to vote in a master election. */
    private List<String> votingConfiguration() throws IOException {
        JsonObject state = client.cluster()
                .state(s -> s.metric("metadata"))
                .valueBody().toJson().asJsonObject();

        JsonArray config = state.getJsonObject("metadata")
                .getJsonObject("cluster_coordination")
                .getJsonArray("last_committed_config");

        return config.stream().map(v -> ((jakarta.json.JsonString) v).getString()).toList();
    }

    /** Node ids of every master-eligible node ("m" in the node.role column). */
    private List<String> masterEligibleNodeIds() throws IOException {
        return client.nodes().info(i -> i).nodes().entrySet().stream()
                .filter(e -> e.getValue().roles().stream()
                        .anyMatch(r -> "master".equals(r.jsonValue())))
                .map(java.util.Map.Entry::getKey)
                .toList();
    }

    @Test
    @DisplayName("exactly one node is master, and all three are master-eligible")
    void oneMasterAmongThreeCandidates() throws IOException {
        var nodes = client.cat().nodes(n -> n);

        List<String> masters = nodes.valueBody().stream()
                .filter(r -> "*".equals(r.master()))
                .map(r -> r.name())
                .toList();

        assertThat(masters).as("there is never more than one elected master").hasSize(1);
        assertThat(masterEligibleNodeIds())
                .as("3 candidates -> quorum is 2 -> the cluster survives losing one")
                .hasSize(3);
    }

    @Test
    @DisplayName("the voting configuration is maintained by the cluster, not configured by hand")
    void votingConfigurationCoversAllCandidates() throws IOException {
        assertThat(votingConfiguration())
                .as("7.x replaced minimum_master_nodes with this self-managed set")
                .hasSize(3)
                .containsExactlyInAnyOrderElementsOf(masterEligibleNodeIds());

        // quorum = floor(n/2) + 1. With 3 voters a 2:1 partition leaves the minority unable
        // to elect anyone - that is the whole split-brain defence.
        assertThat(votingConfiguration().size() / 2 + 1).isEqualTo(2);
    }

    @Test
    @DisplayName("voting exclusions take a node out of the vote - safe master decommissioning")
    void votingExclusionRemovesANodeFromTheVote() throws IOException {
        var nodes = client.nodes().info(i -> i).nodes();
        String currentMaster = client.cat().master(m -> m).valueBody().get(0).node();

        var victim = nodes.entrySet().stream()
                .filter(e -> !e.getValue().name().equals(currentMaster))
                .findFirst()
                .orElseThrow();
        String victimId = victim.getKey();
        String victimName = victim.getValue().name();

        assertThat(votingConfiguration()).contains(victimId);

        client.cluster().postVotingConfigExclusions(e -> e.nodeNames(victimName));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> !votingConfiguration().contains(victimId));

        // Note what it did NOT do: 3 voters minus 1 does not leave 2. Elasticsearch keeps the
        // voting configuration an ODD size (a 2-voter config needs both to agree, which is
        // strictly worse than 1), so it drops to a single voter here. Same reason the notes
        // say to deploy 3 or 5 master candidates, never 4.
        assertThat(votingConfiguration()).doesNotContain(victimId);
        assertThat(votingConfiguration().size() % 2).as("voting config stays odd").isOne();

        // Undo, and the excluded node is voting again.
        client.cluster().deleteVotingConfigExclusions(e -> e.waitForRemoval(false));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> votingConfiguration().size() == 3);
    }

    @Test
    @DisplayName("every node agrees on the same master - no split brain in a healthy cluster")
    void allNodesAgreeOnTheMaster() throws IOException {
        assertThat(client.cluster().health(h -> h).numberOfNodes()).isEqualTo(3);

        String masterPerCatMaster = client.cat().master(m -> m).valueBody().get(0).node();
        List<String> masterPerCatNodes = client.cat().nodes(n -> n).valueBody().stream()
                .filter(r -> "*".equals(r.master()))
                .map(r -> r.name())
                .toList();

        assertThat(masterPerCatNodes).hasSize(1);
        assertThat(masterPerCatMaster).isEqualTo(masterPerCatNodes.get(0));
    }
}
