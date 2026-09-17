package com.pacvue.lab.es.behavior.modeling;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.support.AbstractBehaviorIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;

/**
 * 07-性能调优 §4 / 面试题 Q6.1 - object arrays vs nested.
 *
 * <p>An array of objects is flattened into parallel lists of values, which loses the
 * association between fields of the same element. Queries then match across elements that
 * never belonged together. {@code nested} fixes that by indexing each element as its own
 * hidden document - at a real cost.
 */
class NestedObjectIT extends AbstractBehaviorIT {

    private static final String ORDER_JSON = """
            {
              "orderId": "order-1",
              "items": [
                { "product": "keyboard", "quantity": 1 },
                { "product": "monitor",  "quantity": 5 }
              ]
            }
            """;

    @Override
    protected String indexName() {
        return "it-nested-object";
    }

    @Override
    protected void createIndex() {
        indexOps().create();
        // Default: items is an "object" field, so its values get flattened.
        indexOps().putMapping(Document.parse("""
                {"properties": {
                  "orderId": {"type": "keyword"},
                  "items": {"properties": {
                    "product":  {"type": "keyword"},
                    "quantity": {"type": "integer"}}}}}
                """));
        write(index(), ORDER_JSON);
    }

    private IndexCoordinates nestedIndex() {
        IndexCoordinates target = IndexCoordinates.of(indexName() + "-nested");
        var ops = operations.indexOps(target);
        if (ops.exists()) {
            ops.delete();
        }
        ops.create();
        ops.putMapping(Document.parse("""
                {"properties": {
                  "orderId": {"type": "keyword"},
                  "items": {"type": "nested", "properties": {
                    "product":  {"type": "keyword"},
                    "quantity": {"type": "integer"}}}}}
                """));
        write(target, ORDER_JSON);
        return target;
    }

    private void write(IndexCoordinates target, String json) {
        IndexQuery query = new IndexQueryBuilder().withId("1").withSource(json).build();
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).index(query, target);
    }

    /** keyboard costs 1, monitor costs 5 - no single item is a keyboard with quantity 5. */
    private long matchKeyboardWithQuantityFive(IndexCoordinates target) {
        return operations.count(NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.term(t -> t.field("items.product").value("keyboard")))
                        .must(m -> m.term(t -> t.field("items.quantity").value(5)))))
                .build(), Object.class, target);
    }

    @Test
    @DisplayName("an object array is flattened, so a query matches across different elements")
    void objectArrayLosesTheAssociation() {
        // Internally this became items.product: [keyboard, monitor], items.quantity: [1, 5].
        // Both conditions are satisfied by the document even though no single item satisfies
        // both - the classic false positive.
        assertThat(matchKeyboardWithQuantityFive(index())).isEqualTo(1);
    }

    @Test
    @DisplayName("nested keeps each element together, so the same query correctly misses")
    void nestedPreservesTheAssociation() {
        IndexCoordinates nested = nestedIndex();

        // A plain bool query against a nested field matches nothing at all: nested documents
        // are hidden and only reachable through a nested query.
        assertThat(matchKeyboardWithQuantityFive(nested)).isZero();

        long wrongCombination = operations.count(NativeQuery.builder()
                .withQuery(q -> q.nested(n -> n.path("items")
                        .query(nq -> nq.bool(b -> b
                                .must(m -> m.term(t -> t.field("items.product").value("keyboard")))
                                .must(m -> m.term(t -> t.field("items.quantity").value(5)))))))
                .build(), Object.class, nested);

        long rightCombination = operations.count(NativeQuery.builder()
                .withQuery(q -> q.nested(n -> n.path("items")
                        .query(nq -> nq.bool(b -> b
                                .must(m -> m.term(t -> t.field("items.product").value("monitor")))
                                .must(m -> m.term(t -> t.field("items.quantity").value(5)))))))
                .build(), Object.class, nested);

        assertThat(wrongCombination).as("no single item is a keyboard with quantity 5").isZero();
        assertThat(rightCombination).as("the monitor line does have quantity 5").isEqualTo(1);
    }

    @Test
    @DisplayName("each nested element is a separate Lucene document - that is the cost")
    void nestedElementsAreSeparateDocuments() throws Exception {
        IndexCoordinates nested = nestedIndex();

        var stats = client.indices().stats(s -> s.index(nested.getIndexName()));
        long luceneDocs = stats.indices().get(nested.getIndexName()).total().docs().count();

        // One "order" from the user's point of view, three documents on disk: the parent plus
        // one per item. A document with hundreds of nested elements multiplies accordingly,
        // which is why the notes say to flatten when you can.
        assertThat(luceneDocs).isEqualTo(3);
    }
}
