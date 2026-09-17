package com.pacvue.lab.es.behavior.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch.indices.AnalyzeResponse;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import com.pacvue.lab.es.support.EsErrors;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.SearchHits;

/**
 * 02-存储与索引原理 §2 / 07 §4 / 面试题 Q1.5 — text vs keyword.
 *
 * <p>The single most consequential mapping decision: text is analysed (searchable by word,
 * not aggregatable), keyword is not analysed (exact match, aggregations, sorting).
 */
class TextVsKeywordIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-text-vs-keyword";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class);
    }

    private void seed() {
        operations.save(List.of(
                ArticleDoc.of("1", "The Quick Brown-Fox Jumps", "Nature"),
                ArticleDoc.of("2", "Quick Start Guide", "Docs"),
                ArticleDoc.of("3", "brown sugar recipes", "Food")), index());
        refresh();
    }

    @Test
    @DisplayName("standard analyzer lowercases and splits on punctuation")
    void analyzerPipeline() throws IOException {
        AnalyzeResponse response = client.indices().analyze(a -> a
                .index(indexName())
                .field("title")
                .text("The Quick Brown-Fox!"));

        assertThat(response.tokens()).extracting(t -> t.token())
                .containsExactly("the", "quick", "brown", "fox");
    }

    @Test
    @DisplayName("a keyword field is one single term, punctuation and case included")
    void keywordIsNotAnalysed() throws IOException {
        AnalyzeResponse response = client.indices().analyze(a -> a
                .index(indexName())
                .field("category")
                .text("Home & Garden"));

        assertThat(response.tokens()).extracting(t -> t.token())
                .containsExactly("Home & Garden");
    }

    @Test
    @DisplayName("term on a text field misses, because term does not analyse the input")
    void termAgainstTextFieldIsTheClassicTrap() {
        seed();

        // The document was indexed as [the, quick, brown, fox, jumps]; there is no term
        // "The Quick Brown-Fox Jumps" in the inverted index, so term finds nothing.
        SearchHits<ArticleDoc> exact = operations.search(NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("title").value("The Quick Brown-Fox Jumps")))
                .build(), ArticleDoc.class, index());
        assertThat(exact.getTotalHits()).isZero();

        // Lowercase single token does match, which shows the field really is analysed.
        SearchHits<ArticleDoc> singleTerm = operations.search(NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("title").value("quick")))
                .build(), ArticleDoc.class, index());
        assertThat(singleTerm.getTotalHits()).isEqualTo(2);

        // match analyses the query string the same way the field was analysed, so it works.
        SearchHits<ArticleDoc> matched = operations.search(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("title").query("The Quick Brown-Fox Jumps")))
                .build(), ArticleDoc.class, index());
        assertThat(matched.getTotalHits()).isPositive();
    }

    @Test
    @DisplayName("the keyword sub-field matches the whole value, case sensitively")
    void multiFieldKeywordIsExact() {
        seed();

        assertThat(operations.search(NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("title.keyword").value("Quick Start Guide")))
                .build(), ArticleDoc.class, index()).getTotalHits()).isEqualTo(1);

        // Same value, different case: no hit. Keyword fields are stored verbatim.
        assertThat(operations.search(NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("title.keyword").value("quick start guide")))
                .build(), ArticleDoc.class, index()).getTotalHits()).isZero();
    }

    @Test
    @DisplayName("aggregating on a text field fails: no doc values, fielddata is off")
    void aggregatingOnTextIsRejected() {
        seed();

        Throwable thrown = catchThrowable(() -> operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_title", Aggregation.of(a -> a.terms(t -> t.field("title"))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index()));

        // Spring Data's message is only "all shards failed"; the reason is in the error tree.
        assertThat(EsErrors.typeOf(thrown)).isEqualTo("illegal_argument_exception");
        assertThat(EsErrors.detailOf(thrown))
                .contains("Fielddata is disabled")
                .contains("Please use a keyword field instead");
    }

    @Test
    @DisplayName("aggregating on the keyword sub-field works: that is what doc values are for")
    void aggregatingOnKeywordWorks() {
        seed();

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_category", Aggregation.of(a -> a.terms(t -> t.field("category"))))
                .withMaxResults(0)
                .build(), ArticleDoc.class, index());

        assertThat(hits.hasAggregations()).isTrue();
        assertThat(hits.getSearchHits()).isEmpty(); // size 0: aggregation only, no documents
    }
}
