package com.pacvue.lab.es.behavior.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.query.Query;

/**
 * 03-写入机制 §2,§3 / 面试题 Q2.1 — near real time.
 *
 * <p>"Why can't I search a document I just wrote?" A write lands in an in-memory buffer plus
 * the translog; only a <em>refresh</em> turns that buffer into a searchable segment. Getting
 * by id is a different path and does not wait for refresh.
 */
class NearRealTimeIT extends AbstractBehaviorIT {

    private static final Query MATCH_ALL = NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build();

    @Override
    protected String indexName() {
        return "it-near-real-time";
    }

    @Override
    protected void createIndex() {
        // Automatic refresh off: this test drives visibility explicitly.
        createIndexWithMappingOf(ArticleDoc.class, Map.of("index.refresh_interval", "-1"));
    }

    @Test
    @DisplayName("a freshly written document is not searchable until a refresh happens")
    void writeIsNotVisibleToSearchBeforeRefresh() {
        operations.save(ArticleDoc.of("1", "invisible for now", "News"), index());

        assertThat(operations.count(MATCH_ALL, ArticleDoc.class, index())).isZero();

        refresh();

        assertThat(operations.count(MATCH_ALL, ArticleDoc.class, index())).isEqualTo(1);
    }

    @Test
    @DisplayName("get by id sees the document immediately - it reads the translog, not a segment")
    void getByIdIsRealTime() {
        operations.save(ArticleDoc.of("42", "readable right away", "News"), index());

        // No refresh in between: search would still miss this one.
        assertThat(operations.count(MATCH_ALL, ArticleDoc.class, index())).isZero();

        ArticleDoc found = operations.get("42", ArticleDoc.class, index());
        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("readable right away");
    }

    @Test
    @DisplayName("RefreshPolicy.IMMEDIATE makes the write searchable straight away")
    void immediateRefreshPolicy() {
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .save(ArticleDoc.of("1", "visible immediately", "News"), index());

        assertThat(operations.count(MATCH_ALL, ArticleDoc.class, index())).isEqualTo(1);
    }

    @Test
    @DisplayName("RefreshPolicy.WAIT_UNTIL blocks until the next refresh instead of forcing one")
    void waitUntilRefreshPolicy() throws Exception {
        Thread writer = new Thread(() -> operations.withRefreshPolicy(RefreshPolicy.WAIT_UNTIL)
                .save(ArticleDoc.of("1", "waiting for a refresh", "News"), index()));
        writer.start();

        // refresh_interval is -1, so nothing would ever refresh on its own: the writer stays
        // blocked until something triggers one. That is the cost of WAIT_UNTIL.
        writer.join(2000);
        assertThat(writer.isAlive()).as("WAIT_UNTIL is still pending without a refresh").isTrue();

        refresh();
        writer.join(10_000);
        assertThat(writer.isAlive()).isFalse();
        assertThat(operations.count(MATCH_ALL, ArticleDoc.class, index())).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh_interval controls automatic visibility and can be changed at runtime")
    void refreshIntervalIsAnIndexSetting() throws Exception {
        assertThat(indexOps().getSettings().get("index.refresh_interval")).isEqualTo("-1");

        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.refreshInterval(r -> r.time("1s"))));

        assertThat(indexOps().getSettings().get("index.refresh_interval")).isEqualTo("1s");
    }
}
