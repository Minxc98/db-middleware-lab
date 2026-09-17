package com.pacvue.lab.es.behavior.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.pacvue.lab.es.support.AbstractBehaviorIT;
import com.pacvue.lab.es.support.EsErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.RefreshPolicy;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;

/**
 * 07-性能调优 §4 / 00 §9 常见陷阱 - dynamic mapping.
 *
 * <p>Left on its default, Elasticsearch invents a mapping for every new field it sees. That is
 * convenient until dirty data explodes the mapping and the cluster state with it.
 */
class DynamicMappingIT extends AbstractBehaviorIT {

    @Override
    protected String indexName() {
        return "it-dynamic-mapping";
    }

    @Override
    protected void createIndex() {
        indexOps().create();
    }

    private void write(IndexCoordinates target, String id, String json) {
        IndexQuery query = new IndexQueryBuilder().withId(id).withSource(json).build();
        operations.withRefreshPolicy(RefreshPolicy.IMMEDIATE).index(query, target);
    }

    private long countWhere(IndexCoordinates target, String field, String value) {
        return operations.count(NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field(field).query(value)))
                .build(), Object.class, target);
    }

    private IndexCoordinates indexWithDynamic(String suffix, String dynamic) {
        IndexCoordinates target = IndexCoordinates.of(indexName() + "-" + suffix);
        var ops = operations.indexOps(target);
        if (ops.exists()) {
            ops.delete();
        }
        ops.create();
        ops.putMapping(Document.parse("""
                {"dynamic": "%s", "properties": {"known": {"type": "keyword"}}}
                """.formatted(dynamic)));
        return target;
    }

    @Test
    @DisplayName("dynamic:true (the default) invents a mapping for every unseen field")
    void dynamicTrueAddsFields() {
        write(index(), "1", "{\"brand\": \"acme\", \"price\": 9.99}");

        var mapping = indexOps().getMapping();
        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) mapping.get("properties");

        assertThat(properties).containsKeys("brand", "price");
        // Strings become text + a keyword sub-field; numbers are guessed from the JSON type.
        assertThat(properties.get("brand").toString()).contains("text");
        assertThat(properties.get("price").toString()).contains("float");
    }

    @Test
    @DisplayName("dynamic:false stores the field but never indexes it - silently unsearchable")
    void dynamicFalseStoresButDoesNotIndex() {
        IndexCoordinates target = indexWithDynamic("false", "false");
        write(target, "1", "{\"known\": \"yes\", \"unknown\": \"hidden\"}");

        assertThat(countWhere(target, "known", "yes")).isEqualTo(1);
        // No error, no hit: the value is in _source but not in the inverted index. This is
        // the quiet one - data looks fine until someone tries to search by that field.
        assertThat(countWhere(target, "unknown", "hidden")).isZero();
    }

    @Test
    @DisplayName("dynamic:strict rejects the write instead of guessing")
    void dynamicStrictRejectsUnknownFields() {
        IndexCoordinates target = indexWithDynamic("strict", "strict");

        Throwable thrown = catchThrowable(() ->
                write(target, "1", "{\"known\": \"yes\", \"unknown\": \"boom\"}"));

        assertThat(EsErrors.typeOf(thrown)).isEqualTo("strict_dynamic_mapping_exception");
        assertThat(EsErrors.detailOf(thrown)).contains("mapping set to strict");
    }

    @Test
    @DisplayName("total_fields.limit is the backstop against mapping explosion")
    void fieldLimitStopsMappingExplosion() throws Exception {
        client.indices().putSettings(s -> s.index(indexName())
                .settings(t -> t.mapping(m -> m.totalFields(f -> f.limit("5")))));

        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 20; i++) {
            json.append("\"field_").append(i).append("\": \"v\"").append(i < 19 ? "," : "");
        }
        json.append("}");

        Throwable thrown = catchThrowable(() -> write(index(), "1", json.toString()));

        assertThat(EsErrors.detailOf(thrown))
                .contains("Limit of total fields [5]")
                .contains("has been exceeded");
    }
}
