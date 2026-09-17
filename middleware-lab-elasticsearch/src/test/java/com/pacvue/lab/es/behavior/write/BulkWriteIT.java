package com.pacvue.lab.es.behavior.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pacvue.lab.es.fixture.ArticleDoc;
import com.pacvue.lab.es.support.AbstractBehaviorIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.BulkFailureException;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.IndexedObjectInformation;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;

/**
 * 03-写入机制 §7 - the bulk API.
 *
 * <p>Bulk is how throughput is achieved, and its failure model is per-item: one bad document
 * does not roll back the batch, so the response has to be inspected item by item.
 */
class BulkWriteIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-bulk";
    }

    @Override
    protected void createIndex() {
        createIndexWithMappingOf(ArticleDoc.class, Map.of(
                "index.number_of_shards", 1,
                "index.number_of_replicas", 0));
    }

    private long countAll() {
        return operations.count(NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ArticleDoc.class, index());
    }

    @Test
    @DisplayName("bulkIndex writes a batch and reports what it indexed")
    void bulkIndexesTheWholeBatch() {
        List<IndexQuery> batch = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            batch.add(new IndexQueryBuilder()
                    .withId("doc-" + i)
                    .withObject(ArticleDoc.of("doc-" + i, "article " + i, "news"))
                    .build());
        }

        // Pass IndexCoordinates, not the entity class: the class form would target the index
        // named in @Document instead of the one this test owns.
        List<IndexedObjectInformation> indexed = operations.bulkIndex(batch, index());
        refresh();

        assertThat(indexed).hasSize(100);
        assertThat(countAll()).isEqualTo(100);
    }

    @Test
    @DisplayName("one bad document fails alone - the rest of the batch is still written")
    void oneFailureDoesNotRollBackTheBatch() {
        List<IndexQuery> batch = new ArrayList<>();
        batch.add(new IndexQueryBuilder().withId("ok-1")
                .withSource("{\"title\": \"fine\", \"views\": 1}").build());
        // views is mapped as integer; a string that is not a number fails this item only.
        batch.add(new IndexQueryBuilder().withId("bad")
                .withSource("{\"title\": \"broken\", \"views\": \"not-a-number\"}").build());
        batch.add(new IndexQueryBuilder().withId("ok-2")
                .withSource("{\"title\": \"fine too\", \"views\": 2}").build());

        Throwable thrown = catchThrowable(() -> operations.bulkIndex(batch, index()));
        refresh();

        assertThat(thrown).isInstanceOf(BulkFailureException.class);
        assertThat(((BulkFailureException) thrown).getFailedDocuments()).containsOnlyKeys("bad");

        // There is no transaction: the two valid documents are in the index.
        assertThat(countAll()).isEqualTo(2);
        assertThat(operations.get("ok-1", ArticleDoc.class, index())).isNotNull();
        assertThat(operations.get("bad", ArticleDoc.class, index())).isNull();
    }

    @Test
    @DisplayName("letting Elasticsearch generate ids avoids the internal lookup a given id needs")
    void generatedIds() {
        List<IndexQuery> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(new IndexQueryBuilder()
                    .withSource("{\"title\": \"auto id " + i + "\"}")
                    .build());
        }

        List<IndexedObjectInformation> indexed = operations
                .withRefreshPolicy(RefreshPolicy.IMMEDIATE)
                .bulkIndex(batch, index());

        assertThat(indexed).hasSize(10);
        assertThat(indexed).allSatisfy(info -> assertThat(info.id()).isNotBlank());
        assertThat(countAll()).isEqualTo(10);
    }
}
