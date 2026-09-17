package com.pacvue.lab.es.behavior.query;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;

/**
 * 04-查询机制 §6 / 面试题 Q3.4 - aggregations.
 *
 * <p>Buckets group, metrics compute, and everything reads doc values. The part that bites in
 * production is accuracy: terms aggregations pick top-N per shard before merging, and
 * cardinality is an approximation.
 */
class AggregationIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-aggregation";
    }

    @Override
    protected void createIndex() {
        // Several shards on purpose: that is the precondition for the accuracy caveats below.
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 5,
                "index.number_of_replicas", 0));
    }

    @BeforeEach
    void seed() {
        List<ArticleDoc> docs = new ArrayList<>();
        // 30 audio, 20 input, 10 cables, plus a long tail of one-document categories.
        for (int i = 0; i < 30; i++) {
            docs.add(article("audio-" + i, "audio", 10, "2026-01-01T00:00:00Z"));
        }
        for (int i = 0; i < 20; i++) {
            docs.add(article("input-" + i, "input", 20, "2026-02-01T00:00:00Z"));
        }
        for (int i = 0; i < 10; i++) {
            docs.add(article("cables-" + i, "cables", 30, "2026-03-01T00:00:00Z"));
        }
        for (int i = 0; i < 40; i++) {
            docs.add(article("tail-" + i, "tail-" + i, 1, "2026-03-01T00:00:00Z"));
        }
        operations.save(docs, index());
        refresh();
    }

    private ArticleDoc article(String id, String category, int views, String publishedAt) {
        return new ArticleDoc(id, "title " + id, "body " + id, category, views, Instant.parse(publishedAt));
    }

    private Aggregate aggregate(SearchHits<?> hits, String name) {
        ElasticsearchAggregations aggregations = (ElasticsearchAggregations) hits.getAggregations();
        assertThat(aggregations).isNotNull();
        return aggregations.get(name).aggregation().getAggregate();
    }

    @Test
    @DisplayName("terms buckets group documents; size:0 returns aggregations only")
    void termsBuckets() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_category", Aggregation.of(a -> a.terms(t -> t.field("category").size(3))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        assertThat(hits.getSearchHits()).isEmpty();

        List<StringTermsBucket> buckets = aggregate(hits, "by_category").sterms().buckets().array();
        assertThat(buckets).extracting(b -> b.key().stringValue())
                .containsExactly("audio", "input", "cables");
        assertThat(buckets).extracting(StringTermsBucket::docCount)
                .containsExactly(30L, 20L, 10L);
    }

    @Test
    @DisplayName("metrics nest inside buckets: sum/avg per group")
    void metricsInsideBuckets() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_category", Aggregation.of(a -> a
                        .terms(t -> t.field("category").size(3))
                        .aggregations("total_views", sub -> sub.sum(s -> s.field("views")))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        StringTermsBucket audio = aggregate(hits, "by_category").sterms().buckets().array().get(0);
        assertThat(audio.key().stringValue()).isEqualTo("audio");
        assertThat(audio.aggregations().get("total_views").sum().value()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("terms aggregations are approximate across shards: doc_count_error_upper_bound")
    void termsAccuracyAcrossShards() {
        SearchHits<ArticleDoc> narrow = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_category", Aggregation.of(a -> a.terms(t -> t.field("category").size(2))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        // Each shard sends only its own top-N; what did not make a shard's cut is unknown to
        // the coordinating node, and that uncertainty is reported rather than hidden.
        assertThat(aggregate(narrow, "by_category").sterms().docCountErrorUpperBound()).isNotNull();
        assertThat(aggregate(narrow, "by_category").sterms().sumOtherDocCount()).isPositive();

        // Raising shard_size makes each shard report more candidates, shrinking the error.
        SearchHits<ArticleDoc> wide = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_category", Aggregation.of(a -> a
                        .terms(t -> t.field("category").size(2).shardSize(100))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        assertThat(aggregate(wide, "by_category").sterms().docCountErrorUpperBound())
                .isLessThanOrEqualTo(aggregate(narrow, "by_category").sterms().docCountErrorUpperBound());
    }

    @Test
    @DisplayName("cardinality is a HyperLogLog approximation, exact only for small sets")
    void cardinalityIsApproximate() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("distinct_categories", Aggregation.of(a -> a.cardinality(c -> c.field("category"))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        // 3 real categories + 40 one-off tail values. Below the default precision threshold
        // (40000) the count happens to be exact - above it, it would drift.
        assertThat(aggregate(hits, "distinct_categories").cardinality().value()).isEqualTo(43L);
    }

    @Test
    @DisplayName("date_histogram buckets by calendar interval")
    void dateHistogram() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("per_month", Aggregation.of(a -> a
                        .dateHistogram(d -> d.field("publishedAt").calendarInterval(
                                co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval.Month))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        assertThat(aggregate(hits, "per_month").dateHistogram().buckets().array())
                .hasSize(3)
                .extracting(b -> b.docCount())
                .containsExactly(30L, 20L, 50L);
    }
}
