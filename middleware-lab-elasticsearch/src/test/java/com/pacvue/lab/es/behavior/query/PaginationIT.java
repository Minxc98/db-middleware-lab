package com.pacvue.lab.es.behavior.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import co.elastic.clients.elasticsearch._types.SortOrder;
import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import com.pacvue.lab.es.support.EsErrors;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.Query;

/**
 * 04-查询机制 §5 / 面试题 Q3.2 - paging.
 *
 * <p>from+size is fine for the first few pages and explodes past
 * {@code index.max_result_window}; search_after is the cursor that scales; a PIT freezes the
 * view so a cursor walk is not disturbed by concurrent writes.
 */
class PaginationIT extends AbstractBehaviorIT {

    private static final int TOTAL = 50;

    @Override
    protected String indexName() {
        return "it-pagination";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 2,
                "index.number_of_replicas", 0));
    }

    @BeforeEach
    void seed() {
        List<ArticleDoc> docs = new ArrayList<>();
        for (int i = 1; i <= TOTAL; i++) {
            docs.add(new ArticleDoc(String.format("%03d", i), "article " + i, "body " + i,
                    "news", i, Instant.parse("2026-01-01T00:00:00Z")));
        }
        operations.save(docs, index());
        refresh();
    }

    @Test
    @DisplayName("from+size past max_result_window is refused")
    void deepFromSizeIsRejected() {
        Throwable thrown = catchThrowable(() -> operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withPageable(PageRequest.of(1001, 10))
                .build(), ArticleDoc.class, index()));

        assertThat(EsErrors.typeOf(thrown)).isEqualTo("illegal_argument_exception");
        assertThat(EsErrors.detailOf(thrown))
                .contains("Result window is too large")
                .contains("index.max_result_window");
    }

    @Test
    @DisplayName("raising max_result_window makes it work again - and shows why it is capped")
    void maxResultWindowIsAnIndexSetting() throws Exception {
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.maxResultWindow(20000)));

        SearchHits<ArticleDoc> hits = operations.search(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withPageable(PageRequest.of(1001, 10))
                .build(), ArticleDoc.class, index());

        assertThat(hits.getTotalHits()).isEqualTo(TOTAL);
        assertThat(hits.getSearchHits()).isEmpty();
    }

    @Test
    @DisplayName("search_after walks pages by cursor, with a tiebreaker for a stable order")
    void searchAfterCursor() {
        List<String> collected = new ArrayList<>();
        List<Object> cursor = null;

        for (int page = 0; page < 10; page++) {
            NativeQueryBuilder builder = NativeQuery.builder()
                    .withQuery(q -> q.matchAll(m -> m))
                    .withSort(s -> s.field(f -> f.field("views").order(SortOrder.Asc)))
                    // _id cannot be used as a sort key in ES 8 (fielddata on _id is disallowed),
                    // so the unique keyword sub-field plays the tiebreaker role.
                    .withSort(s -> s.field(f -> f.field("title.keyword").order(SortOrder.Asc)))
                    .withMaxResults(10);
            if (cursor != null) {
                builder.withSearchAfter(cursor);
            }

            SearchHits<ArticleDoc> hits = operations.search(builder.build(), ArticleDoc.class, index());
            if (!hits.hasSearchHits()) {
                break;
            }
            hits.getSearchHits().forEach(h -> collected.add(h.getId()));
            cursor = hits.getSearchHit(hits.getSearchHits().size() - 1).getSortValues();
        }

        assertThat(collected).hasSize(TOTAL).doesNotHaveDuplicates();
        assertThat(collected.get(0)).isEqualTo("001");
        assertThat(collected.get(TOTAL - 1)).isEqualTo(String.format("%03d", TOTAL));
    }

    @Test
    @DisplayName("a point in time freezes the view against concurrent writes")
    void pointInTimeFreezesTheView() {
        String pit = operations.openPointInTime(index(), Duration.ofMinutes(1));
        try {
            SearchHits<ArticleDoc> firstPage = operations.search(NativeQuery.builder()
                    .withQuery(q -> q.matchAll(m -> m))
                    .withSort(s -> s.field(f -> f.field("views").order(SortOrder.Asc)))
                    .withPointInTime(new Query.PointInTime(pit, Duration.ofMinutes(1)))
                    .withMaxResults(10)
                    .build(), ArticleDoc.class);

            assertThat(firstPage.getTotalHits()).isEqualTo(TOTAL);

            operations.save(List.of(new ArticleDoc("999", "added later", "body", "news", 999,
                    Instant.parse("2026-01-01T00:00:00Z"))), index());
            refresh();

            SearchHits<ArticleDoc> throughPit = operations.search(NativeQuery.builder()
                    .withQuery(q -> q.matchAll(m -> m))
                    .withPointInTime(new Query.PointInTime(pit, Duration.ofMinutes(1)))
                    .withMaxResults(0)
                    .build(), ArticleDoc.class);

            assertThat(throughPit.getTotalHits()).isEqualTo(TOTAL);
            assertThat(operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                    ArticleDoc.class, index())).isEqualTo(TOTAL + 1);
        } finally {
            operations.closePointInTime(pit);
        }
    }

    @Test
    @DisplayName("searchForStream pages through everything without manual cursor handling")
    void streamingExport() {
        List<String> ids = new ArrayList<>();
        // No maxResults here: for searchForStream that is a hard cap on the total, not a
        // batch size, so setting it would silently truncate the export.
        try (SearchHitsIterator<ArticleDoc> stream = operations.searchForStream(NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .build(), ArticleDoc.class, index())) {
            stream.forEachRemaining((SearchHit<ArticleDoc> hit) -> ids.add(hit.getId()));
        }

        assertThat(ids).hasSize(TOTAL).doesNotHaveDuplicates();
    }
}
