package com.pacvue.lab.es.behavior.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

/**
 * 04-查询机制 §3 — relevance scoring with BM25.
 *
 * <p>Three factors decide {@code _score}: term frequency (with saturation), inverse document
 * frequency, and field-length normalisation.
 */
class RelevanceScoringIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-relevance";
    }

    @Override
    protected void createIndex() {
        // One shard: IDF is computed per shard, so multiple shards would make scores from
        // different documents incomparable. That caveat is itself worth knowing.
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0));
    }

    private ArticleDoc article(String id, String body) {
        return new ArticleDoc(id, "title " + id, body, "test", 0, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("term frequency: more occurrences score higher, but with saturation")
    void termFrequencySaturates() {
        operations.save(List.of(
                article("once", "elasticsearch"),
                article("thrice", "elasticsearch elasticsearch elasticsearch"),
                article("many", ("elasticsearch ").repeat(50))), index());
        refresh();

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("elasticsearch")))
                .build(), ArticleDoc.class, index());

        Map<String, Float> scores = scoresById(hits);
        assertThat(scores.get("thrice")).isGreaterThan(scores.get("once"));

        // BM25 saturates: 50 occurrences are worth far less than 50x three occurrences.
        float gainFromOneToThree = scores.get("thrice") - scores.get("once");
        float gainFromThreeToFifty = scores.get("many") - scores.get("thrice");
        assertThat(gainFromThreeToFifty).isLessThan(gainFromOneToThree * 5);
    }

    @Test
    @DisplayName("inverse document frequency: a rare term counts for more than a common one")
    void rareTermsWeighMore() {
        // "common" appears everywhere, "rare" only in one document.
        operations.save(List.of(
                article("1", "common rare"),
                article("2", "common"),
                article("3", "common"),
                article("4", "common"),
                article("5", "common")), index());
        refresh();

        float rareScore = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("rare")))
                .build(), ArticleDoc.class, index()).getSearchHit(0).getScore();

        float commonScore = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("common")))
                .build(), ArticleDoc.class, index()).getSearchHit(0).getScore();

        assertThat(rareScore).isGreaterThan(commonScore);
    }

    @Test
    @DisplayName("field-length normalisation: the same hit in a shorter field scores higher")
    void shorterFieldsScoreHigher() {
        operations.save(List.of(
                article("short", "elasticsearch"),
                article("long", "elasticsearch " + "filler word here ".repeat(30))), index());
        refresh();

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("elasticsearch")))
                .build(), ArticleDoc.class, index());

        assertThat(hits.getSearchHit(0).getId()).isEqualTo("short");
        Map<String, Float> scores = scoresById(hits);
        assertThat(scores.get("short")).isGreaterThan(scores.get("long"));
    }

    @Test
    @DisplayName("explain shows the BM25 factors that produced the score")
    void explainExposesTheScoreBreakdown() {
        operations.save(List.of(article("1", "elasticsearch scoring")), index());
        refresh();

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("elasticsearch")))
                .withExplain(true)
                .build(), ArticleDoc.class, index());

        var explanation = hits.getSearchHit(0).getExplanation();
        assertThat(explanation).isNotNull();
        // The whole BM25 formula is spelled out in the explanation tree, including the two
        // tuning parameters behind the behaviour the tests above assert on:
        // k1 (term-frequency saturation) and b (field-length normalisation).
        assertThat(explanation.toString())
                .contains("idf, computed as log(1 + (N - n + 0.5) / (n + 0.5))")
                .contains("tf, computed as freq / (freq + k1 *")
                .contains("k1, term saturation parameter")
                .contains("b, length normalization parameter")
                .contains("avgdl, average length of field");
    }

    private Map<String, Float> scoresById(SearchHits<ArticleDoc> hits) {
        return hits.getSearchHits().stream()
                .collect(java.util.stream.Collectors.toMap(SearchHit::getId, SearchHit::getScore));
    }
}
