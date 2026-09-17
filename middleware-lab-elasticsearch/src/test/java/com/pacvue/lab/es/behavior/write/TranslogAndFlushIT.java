package com.pacvue.lab.es.behavior.write;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import co.elastic.clients.elasticsearch.indices.TranslogDurability;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 02-存储与索引原理 §6 / 03-写入机制 §4 — translog and flush.
 *
 * <p>refresh makes data <em>searchable</em>; flush makes it <em>durable</em>. Between the two,
 * the translog is what would be replayed after a crash.
 */
class TranslogAndFlushIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-translog";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0));
    }

    private long uncommittedTranslogOps() throws IOException {
        IndicesStatsResponse stats = client.indices().stats(s -> s.index(indexName()));
        return stats.indices().get(indexName()).total().translog().uncommittedOperations();
    }

    @Test
    @DisplayName("writes accumulate in the translog and a flush clears it")
    void flushClearsTheTranslog() throws IOException {
        for (int i = 1; i <= 10; i++) {
            operations.save(ArticleDoc.of(String.valueOf(i), "article " + i, "News"), index());
        }

        // Not yet flushed: these operations are what a crash recovery would replay.
        assertThat(uncommittedTranslogOps()).isEqualTo(10);

        client.indices().flush(f -> f.index(indexName()));

        // Flushed: the segments are fsynced, so the log protecting them is no longer needed.
        assertThat(uncommittedTranslogOps()).isZero();
    }

    @Test
    @DisplayName("a refresh alone does not clear the translog - searchable is not durable")
    void refreshDoesNotFlush() throws IOException {
        operations.save(ArticleDoc.of("1", "searchable but not fsynced", "News"), index());

        refresh();

        assertThat(operations.get("1", ArticleDoc.class, index())).isNotNull();
        assertThat(uncommittedTranslogOps())
                .as("refresh made it visible, but the translog still guards it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("translog durability is an index setting: request (safe) vs async (fast)")
    void durabilityIsConfigurable() throws IOException {
        // Default: fsync the translog on every request - safe, slightly slower.
        assertThat(indexOps().getSettings(true).get("index.translog.durability"))
                .isEqualTo("request");

        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.translog(tl -> tl.durability(TranslogDurability.Async))));

        assertThat(indexOps().getSettings().get("index.translog.durability")).isEqualTo("async");
    }
}
