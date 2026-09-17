package com.pacvue.lab.es.behavior.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import com.pacvue.lab.es.support.EsErrors;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

/**
 * 05-分片与路由 §2 / 面试题 Q4.1 - why the primary shard count is fixed.
 *
 * <p>The routing formula divides by the number of primary shards, so changing it would send
 * every existing id to a different shard. The supported ways out are reindex, split and
 * shrink - all of which produce a <em>new</em> index.
 */
class IndexSettingsIT extends AbstractBehaviorIT {

    private static final String SPLIT_TARGET = "it-index-settings-split";
    private static final String SHRINK_TARGET = "it-index-settings-shrink";

    @Override
    protected String indexName() {
        return "it-index-settings";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 2,
                "index.number_of_replicas", 0));
    }

    @AfterEach
    void dropDerivedIndices() {
        for (String name : List.of(SPLIT_TARGET, SHRINK_TARGET)) {
            var ops = operations.indexOps(IndexCoordinates.of(name));
            if (ops.exists()) {
                ops.delete();
            }
        }
    }

    private void seed(int count) {
        List<ArticleDoc> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            docs.add(new ArticleDoc("doc-" + i, "title " + i, "body", "news", i,
                    Instant.parse("2026-01-01T00:00:00Z")));
        }
        operations.save(docs, index());
        refresh();
    }

    private long countIn(String name) {
        return operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, IndexCoordinates.of(name));
    }

    @Test
    @DisplayName("number_of_shards cannot be changed on an existing index, open or closed")
    void primaryShardCountIsFinal() throws IOException {
        Throwable onOpenIndex = catchThrowable(() -> client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfShards("4"))));

        assertThat(EsErrors.statusOf(onOpenIndex)).isEqualTo(400);
        assertThat(EsErrors.detailOf(onOpenIndex))
                .contains("Can't update non dynamic settings")
                .contains("index.number_of_shards");

        // The message suggests closing the index and retrying. Follow that advice: it still
        // fails, because the shard count is a final setting, not merely a static one - the
        // routing formula hash(_routing) % number_of_primary_shards means changing it would
        // send every existing id to a different shard.
        client.indices().close(c -> c.index(indexName()));
        try {
            Throwable onClosedIndex = catchThrowable(() -> client.indices()
                    .putSettings(s -> s.index(indexName()).settings(t -> t.numberOfShards("4"))));

            assertThat(EsErrors.statusOf(onClosedIndex)).isEqualTo(400);
            assertThat(EsErrors.detailOf(onClosedIndex)).contains("final");
        } finally {
            client.indices().open(o -> o.index(indexName()));
        }
    }

    @Test
    @DisplayName("number_of_replicas can be changed at any time - replicas are not in the formula")
    void replicaCountIsDynamic() throws IOException {
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("1")));

        assertThat(indexOps().getSettings().get("index.number_of_replicas")).isEqualTo("1");

        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("0")));
        assertThat(indexOps().getSettings().get("index.number_of_replicas")).isEqualTo("0");
    }

    @Test
    @DisplayName("split multiplies the shard count into a new index, data intact")
    void splitIntoMoreShards() throws IOException {
        seed(20);

        // Splitting requires the source to stop accepting writes first.
        client.indices().addBlock(b -> b.index(indexName()).block(
                co.elastic.clients.elasticsearch.indices.add_block.IndicesBlockOptions.Write));

        client.indices().split(s -> s.index(indexName()).target(SPLIT_TARGET)
                .settings("index.number_of_shards", co.elastic.clients.json.JsonData.of(4)));

        operations.indexOps(IndexCoordinates.of(SPLIT_TARGET)).refresh();

        assertThat(operations.indexOps(IndexCoordinates.of(SPLIT_TARGET)).getSettings()
                .get("index.number_of_shards")).isEqualTo("4");
        assertThat(countIn(SPLIT_TARGET)).isEqualTo(20);
    }

    @Test
    @DisplayName("shrink divides the shard count into a new index, data intact")
    void shrinkIntoFewerShards() throws IOException {
        seed(20);

        client.indices().addBlock(b -> b.index(indexName()).block(
                co.elastic.clients.elasticsearch.indices.add_block.IndicesBlockOptions.Write));

        client.indices().shrink(s -> s.index(indexName()).target(SHRINK_TARGET)
                .settings("index.number_of_shards", co.elastic.clients.json.JsonData.of(1)));

        operations.indexOps(IndexCoordinates.of(SHRINK_TARGET)).refresh();

        assertThat(operations.indexOps(IndexCoordinates.of(SHRINK_TARGET)).getSettings()
                .get("index.number_of_shards")).isEqualTo("1");
        assertThat(countIn(SHRINK_TARGET)).isEqualTo(20);
    }

    @Test
    @DisplayName("an alias lets readers follow a reindex without knowing the index name")
    void aliasSwitchesReadersToANewIndex() throws IOException {
        seed(5);
        String alias = "it-index-settings-alias";

        client.indices().updateAliases(u -> u.actions(a -> a.add(add -> add
                .index(indexName()).alias(alias))));

        assertThat(countIn(alias)).isEqualTo(5);

        // Reindex into an index with a different shard count, then move the alias atomically.
        client.indices().create(c -> c.index(SPLIT_TARGET)
                .settings(st -> st.numberOfShards("4").numberOfReplicas("0")));
        client.reindex(r -> r.source(src -> src.index(indexName())).dest(d -> d.index(SPLIT_TARGET))
                .refresh(true));

        client.indices().updateAliases(u -> u
                .actions(a -> a.remove(rm -> rm.index(indexName()).alias(alias)))
                .actions(a -> a.add(add -> add.index(SPLIT_TARGET).alias(alias))));

        assertThat(countIn(alias)).isEqualTo(5);
        // Settings are read from the concrete index; the alias is only a pointer.
        assertThat(operations.indexOps(IndexCoordinates.of(SPLIT_TARGET)).getSettings()
                .get("index.number_of_shards")).isEqualTo("4");
    }
}
