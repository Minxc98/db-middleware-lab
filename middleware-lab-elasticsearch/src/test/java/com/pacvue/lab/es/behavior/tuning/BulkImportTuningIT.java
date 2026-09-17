package com.pacvue.lab.es.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;

/**
 * 07-性能调优 §2 - the bulk import "golden configuration".
 *
 * <p>Before a large import: no replicas, no automatic refresh. Afterwards: put them back and
 * force merge the now read-only index. This test walks that sequence and checks each step
 * actually took effect.
 */
class BulkImportTuningIT extends AbstractBehaviorIT {

    private static final int DOCS = 500;

    @Override
    protected String indexName() {
        return "it-bulk-import-tuning";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 1,
                "index.refresh_interval", "1s"));
    }

    @Test
    @DisplayName("import with replicas and refresh disabled, then restore and force merge")
    void goldenImportSequence() throws IOException {
        // --- before the import -------------------------------------------------------
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("0").refreshInterval(r -> r.time("-1"))));

        assertThat(indexOps().getSettings().get("index.number_of_replicas")).isEqualTo("0");
        assertThat(indexOps().getSettings().get("index.refresh_interval")).isEqualTo("-1");

        // --- the import --------------------------------------------------------------
        List<IndexQuery> batch = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            batch.add(new IndexQueryBuilder()
                    .withId("doc-" + i)
                    .withObject(ArticleDoc.of("doc-" + i, "article " + i, "news"))
                    .build());
        }
        operations.bulkIndex(batch, index());

        // Nothing is searchable yet - that is the point of turning refresh off.
        assertThat(countAll()).isZero();

        // --- after the import --------------------------------------------------------
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.numberOfReplicas("1").refreshInterval(r -> r.time("30s"))));
        refresh();

        assertThat(countAll()).isEqualTo(DOCS);
        assertThat(indexOps().getSettings().get("index.refresh_interval")).isEqualTo("30s");

        // A finished, read-only index is the one case where force merge is worth its cost.
        client.indices().forcemerge(f -> f.index(indexName()).maxNumSegments(1L));
        refresh();

        var stats = client.indices().stats(s -> s.index(indexName()));
        assertThat(stats.indices().get(indexName()).primaries().segments().count()).isEqualTo(1);
        assertThat(countAll()).isEqualTo(DOCS);
    }

    private long countAll() {
        return operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, index());
    }
}
