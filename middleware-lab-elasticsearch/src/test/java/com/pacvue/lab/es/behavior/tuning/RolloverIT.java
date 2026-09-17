package com.pacvue.lab.es.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

/**
 * 07-性能调优 §5 / 05 §3 - rollover, the mechanism under ILM.
 *
 * <p>Time-series data is not kept in one ever-growing index: a write alias points at the
 * current index, and rollover creates the next one when a condition is met. Because each
 * rollover produces a new index, this is also how the "immutable" primary shard count gets
 * changed in practice.
 */
class RolloverIT extends AbstractBehaviorIT {

    private static final String ALIAS = "it-rollover";
    private static final String FIRST = "it-rollover-000001";
    private static final String SECOND = "it-rollover-000002";

    @Override
    protected String indexName() {
        return FIRST;
    }

    @Override
    protected void createIndex() {
        try {
            for (String name : List.of(SECOND, FIRST)) {
                var ops = operations.indexOps(IndexCoordinates.of(name));
                if (ops.exists()) {
                    ops.delete();
                }
            }
            client.indices().create(c -> c
                    .index(FIRST)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .aliases(ALIAS, a -> a.isWriteIndex(true)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void dropSecond() {
        var ops = operations.indexOps(IndexCoordinates.of(SECOND));
        if (ops.exists()) {
            ops.delete();
        }
    }

    private void write(String id) {
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(ArticleDoc.of(id, "entry " + id, "logs"), IndexCoordinates.of(ALIAS));
    }

    private long countIn(String target) {
        return operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, IndexCoordinates.of(target));
    }

    @Test
    @DisplayName("rollover moves the write alias to a new index; reads still see everything")
    void rolloverSwitchesTheWriteIndex() throws IOException {
        write("1");
        write("2");
        assertThat(countIn(FIRST)).isEqualTo(2);

        var response = client.indices().rollover(r -> r.alias(ALIAS)
                .conditions(c -> c.maxDocs(2L)));

        assertThat(response.rolledOver()).isTrue();
        assertThat(response.newIndex()).isEqualTo(SECOND);

        // New writes land in the new index...
        write("3");
        assertThat(countIn(FIRST)).isEqualTo(2);
        assertThat(countIn(SECOND)).isEqualTo(1);

        // ...while a read through the alias spans both, which is what makes the switch
        // invisible to the query side.
        assertThat(countIn(ALIAS)).isEqualTo(3);
    }

    @Test
    @DisplayName("rollover does nothing while the condition is not met")
    void rolloverIsConditional() throws IOException {
        write("1");

        var response = client.indices().rollover(r -> r.alias(ALIAS)
                .conditions(c -> c.maxDocs(1000L)));

        assertThat(response.rolledOver()).isFalse();
        assertThat(operations.indexOps(IndexCoordinates.of(SECOND)).exists()).isFalse();
    }

    @Test
    @DisplayName("the new index can have a different shard count - the supported way to change it")
    void rolloverCanChangeShardCount() throws IOException {
        write("1");

        client.indices().rollover(r -> r.alias(ALIAS)
                .conditions(c -> c.maxDocs(1L))
                .settings("index.number_of_shards", co.elastic.clients.json.JsonData.of(3)));

        assertThat(operations.indexOps(IndexCoordinates.of(SECOND)).getSettings()
                .get("index.number_of_shards")).isEqualTo("3");
        assertThat(operations.indexOps(IndexCoordinates.of(FIRST)).getSettings()
                .get("index.number_of_shards")).isEqualTo("1");
    }
}
