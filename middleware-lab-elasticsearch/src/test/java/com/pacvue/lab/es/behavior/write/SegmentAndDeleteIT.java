package com.pacvue.lab.es.behavior.write;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.indices.IndicesStatsResponse;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.RefreshPolicy;

/**
 * 02-存储与索引原理 §3 / 03-写入机制 §5 / 面试题 Q2.3 — segments are immutable.
 *
 * <p>Consequences worth pinning down: an update is a delete plus an insert, a delete only
 * marks the document, and the space comes back at merge time - not at delete time.
 */
class SegmentAndDeleteIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-segments";
    }

    @Override
    protected void createIndex() {
        // One shard keeps the numbers easy to reason about.
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0,
                // Soft-delete tombstones are normally retained for 12h so that peer recovery
                // and CCR can replay them; with a lease that long, a merge reclaims nothing.
                // Zero here makes the reclaim observable inside a test.
                "index.soft_deletes.retention_lease.period", "0s"));
    }

    private long deletedDocs() throws IOException {
        IndicesStatsResponse stats = client.indices().stats(s -> s.index(indexName()));
        return stats.indices().get(indexName()).total().docs().deleted();
    }

    private long liveDocs() throws IOException {
        IndicesStatsResponse stats = client.indices().stats(s -> s.index(indexName()));
        return stats.indices().get(indexName()).total().docs().count();
    }

    private long segmentCount() throws IOException {
        IndicesStatsResponse stats = client.indices().stats(s -> s.index(indexName()));
        return stats.indices().get(indexName()).total().segments().count();
    }

    @Test
    @DisplayName("updating a document leaves the old version behind as a deleted doc")
    void updateIsDeletePlusInsert() throws IOException {
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(ArticleDoc.of("1", "first version", "News"), index());
        assertThat(deletedDocs()).isZero();

        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(ArticleDoc.of("1", "second version", "News"), index());

        // Still one live document, but the superseded copy is physically still there.
        assertThat(operations.get("1", ArticleDoc.class, index()).getTitle()).isEqualTo("second version");
        assertThat(deletedDocs()).isEqualTo(1);
    }

    @Test
    @DisplayName("delete is a soft delete: the document is only marked, not removed")
    void deleteIsSoft() throws IOException {
        operations.save(List.of(
                ArticleDoc.of("1", "keep me", "News"),
                ArticleDoc.of("2", "delete me", "News")), index());
        refresh();

        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).delete("2", index());

        assertThat(liveDocs()).isEqualTo(1);
        // Physically still on disk. Note a delete leaves more behind than an update does:
        // on top of marking the document, ES keeps a soft-delete tombstone that peer recovery
        // and CCR replay from, and a retention lease keeps that tombstone around - so even
        // force merge will not necessarily bring this back to zero.
        assertThat(deletedDocs()).isPositive();
    }

    @Test
    @DisplayName("force merge does NOT immediately reclaim soft-deleted documents")
    void forceMergeDoesNotImmediatelyReclaimSoftDeletes() throws IOException {
        for (int version = 1; version <= 5; version++) {
            operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                    .save(ArticleDoc.of("1", "version " + version, "News"), index());
        }

        // Four superseded copies of the same document are occupying space.
        assertThat(deletedDocs()).isEqualTo(4);

        client.indices().forcemerge(f -> f.index(indexName()).maxNumSegments(1L));
        refresh();

        // The merge happened - everything is in one segment now.
        assertThat(segmentCount()).isEqualTo(1);

        // But the space is still not back. Since 7.x soft deletes keep deleted/superseded
        // documents for peer recovery and CCR, governed by a retention lease
        // (index.soft_deletes.retention_lease.period, default 12h, synced every 5m). Setting
        // the period to 0s - as this index does - does not take effect within a test either,
        // because the lease is only renewed on that sync interval.
        //
        // This is the practical answer to "I deleted a lot of data and force merged, why is
        // the disk still full": merging is necessary but not sufficient.
        assertThat(deletedDocs())
                .as("soft-deleted docs survive the merge until their retention lease expires")
                .isEqualTo(4);

        // The live view is unaffected throughout.
        assertThat(liveDocs()).isEqualTo(1);
        assertThat(operations.get("1", ArticleDoc.class, index()).getTitle()).isEqualTo("version 5");
    }

    @Test
    @DisplayName("each refresh creates a segment; force merge collapses them into one")
    void refreshCreatesSegmentsAndMergeCollapsesThem() throws IOException {
        for (int i = 1; i <= 5; i++) {
            operations.save(ArticleDoc.of(String.valueOf(i), "article " + i, "News"), index());
            refresh(); // one segment per refresh
        }

        assertThat(segmentCount()).isGreaterThan(1);

        client.indices().forcemerge(f -> f.index(indexName()).maxNumSegments(1L));
        refresh();

        assertThat(segmentCount()).isEqualTo(1);
        // Merging changes physical layout only - the documents are all still searchable.
        assertThat(operations.count(
                org.springframework.data.elasticsearch.client.elc.NativeQuery.builder()
                        .withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, index())).isEqualTo(5);
    }
}
