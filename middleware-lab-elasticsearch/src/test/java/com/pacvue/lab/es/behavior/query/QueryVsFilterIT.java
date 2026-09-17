package com.pacvue.lab.es.behavior.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;

/**
 * 04-查询机制 §1,§2 / 面试题 Q3.3 — query context vs filter context.
 *
 * <p>Same matching, different accounting: a query clause contributes to {@code _score} and is
 * not cached, a filter clause only decides yes/no and is cacheable. Anything that is a plain
 * condition belongs in filter.
 */
class QueryVsFilterIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-query-vs-filter";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class);
    }

    @BeforeEach
    void seed() {
        operations.save(List.of(
                new ArticleDoc("1", "wireless headphones", "great wireless sound", "audio", 100,
                        Instant.parse("2026-01-01T00:00:00Z")),
                new ArticleDoc("2", "wireless mouse", "a mouse without wires", "input", 50,
                        Instant.parse("2026-02-01T00:00:00Z")),
                new ArticleDoc("3", "usb cable", "plain cable", "cables", 10,
                        Instant.parse("2026-03-01T00:00:00Z"))), index());
        refresh();
    }

    @Test
    @DisplayName("a must clause scores; the same clause as filter yields a constant score")
    void filterDoesNotContributeToScore() {
        SearchHits<ArticleDoc> scored = operations.search(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.must(m -> m.match(mm -> mm.field("title").query("wireless")))))
                .build(), ArticleDoc.class, index());

        SearchHits<ArticleDoc> filtered = operations.search(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b.filter(f -> f.match(mm -> mm.field("title").query("wireless")))))
                .build(), ArticleDoc.class, index());

        assertThat(scored.getTotalHits()).isEqualTo(2);
        assertThat(filtered.getTotalHits()).isEqualTo(2);

        assertThat(scored.getSearchHits()).allSatisfy(h -> assertThat(h.getScore()).isGreaterThan(0f));
        // Filter clauses only decide membership, so every hit gets the same neutral score.
        assertThat(filtered.getSearchHits()).extracting(h -> h.getScore()).containsOnly(0.0f);
    }

    @Test
    @DisplayName("bool combines must / should / filter / must_not with distinct meanings")
    void boolClauseSemantics() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm.field("title").query("wireless")))   // required, scored
                        .filter(f -> f.range(r -> r.number(n -> n.field("views").gte(20.0))))  // required, not scored
                        .mustNot(mn -> mn.term(t -> t.field("category").value("input")))))     // excluded
                .build(), ArticleDoc.class, index());

        assertThat(hits.getSearchHits()).extracting(h -> h.getId()).containsExactly("1");
    }

    @Test
    @DisplayName("should raises the score of matching documents without excluding the others")
    void shouldAffectsRankingNotMembership() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.matchAll(ma -> ma))
                        .should(s -> s.term(t -> t.field("category").value("cables")))))
                .build(), ArticleDoc.class, index());

        assertThat(hits.getTotalHits()).isEqualTo(3);
        // The should clause did not filter anything out, it only pushed doc 3 to the top.
        assertThat(hits.getSearchHit(0).getId()).isEqualTo("3");
    }

    @Test
    @DisplayName("minimum_should_match turns should clauses into a requirement")
    void minimumShouldMatch() {
        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .should(s -> s.term(t -> t.field("category").value("cables")))
                        .should(s -> s.term(t -> t.field("category").value("audio")))
                        .minimumShouldMatch("1")))
                .build(), ArticleDoc.class, index());

        assertThat(hits.getTotalHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("match_phrase requires the words to be adjacent and in order")
    void matchPhraseRespectsWordOrder() {
        long anyOrder = operations.count(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("body").query("wireless great")))
                .build(), ArticleDoc.class, index());

        long phrase = operations.count(NativeQuery.builder()
                .withQuery(q -> q.matchPhrase(m -> m.field("body").query("wireless great")))
                .build(), ArticleDoc.class, index());

        long correctOrder = operations.count(NativeQuery.builder()
                .withQuery(q -> q.matchPhrase(m -> m.field("body").query("great wireless")))
                .build(), ArticleDoc.class, index());

        assertThat(anyOrder).isEqualTo(1);   // match ignores order
        assertThat(phrase).isZero();          // wrong order: no phrase match
        assertThat(correctOrder).isEqualTo(1);
    }

    @Test
    @DisplayName("range, terms and exists cover the everyday filter cases")
    void rangeTermsExists() {
        assertThat(operations.count(NativeQuery.builder()
                .withQuery(q -> q.range(r -> r.number(n -> n.field("views").gte(50.0).lte(100.0))))
                .build(), ArticleDoc.class, index())).isEqualTo(2);

        assertThat(operations.count(NativeQuery.builder()
                .withQuery(q -> q.terms(t -> t.field("category")
                        .terms(v -> v.value(List.of(
                                co.elastic.clients.elasticsearch._types.FieldValue.of("audio"),
                                co.elastic.clients.elasticsearch._types.FieldValue.of("cables"))))))
                .build(), ArticleDoc.class, index())).isEqualTo(2);

        assertThat(operations.count(NativeQuery.builder()
                .withQuery(q -> q.exists(e -> e.field("publishedAt")))
                .build(), ArticleDoc.class, index())).isEqualTo(3);
    }
}
