package com.pacvue.lab.es.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the behaviour suite: one index per test class, recreated before each test.
 *
 * <p>Everything goes through Spring Data's {@link ElasticsearchOperations}. The low-level
 * {@link ElasticsearchClient} (auto-configured by Boot and what Spring Data itself runs on) is
 * also available for the admin-side APIs Spring Data does not wrap - analyze, force merge,
 * flush, index stats, cluster health, snapshots.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractBehaviorIT {

    @Autowired
    protected ElasticsearchOperations operations;

    @Autowired
    protected ElasticsearchClient client;

    /** Index this test class owns. Keep it unique per class so classes cannot disturb each other. */
    protected abstract String indexName();

    protected IndexCoordinates index() {
        return IndexCoordinates.of(indexName());
    }

    protected IndexOperations indexOps() {
        return operations.indexOps(index());
    }

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ElasticsearchContainerFactory::httpUri);
    }

    @BeforeEach
    void dropIndex() {
        IndexOperations ops = indexOps();
        if (ops.exists()) {
            ops.delete();
        }
        createIndex();
    }

    /**
     * Creates the index this test class needs. Override to supply settings/mappings;
     * the default lets Elasticsearch infer everything dynamically.
     */
    protected void createIndex() {
        indexOps().create();
    }

    /**
     * Creates this test's index with the mapping derived from {@code type}.
     *
     * <p>{@code indexOps(IndexCoordinates)} is "unbound" - it has no entity to derive a mapping
     * from, so {@code createWithMapping()} on it fails with "IndexOperations are not bound".
     * The mapping therefore comes from the entity-bound operations and is applied to our index.
     */
    protected void createIndexWithMappingOf(Class<?> type) {
        createIndexWithMappingOf(type, Map.of());
    }

    protected void createIndexWithMappingOf(Class<?> type, Map<String, Object> settings) {
        IndexOperations target = indexOps();
        target.create(settings);
        target.putMapping(operations.indexOps(type).createMapping(type));
    }

    /** Makes everything written so far visible to search. */
    protected void refresh() {
        indexOps().refresh();
    }
}
