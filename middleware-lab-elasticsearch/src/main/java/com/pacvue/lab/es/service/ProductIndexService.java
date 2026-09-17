package com.pacvue.lab.es.service;

import com.pacvue.lab.es.config.EsLabProperties;
import com.pacvue.lab.es.domain.ProductDoc;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;

/**
 * Index lifecycle and write path: everything a test needs to get documents into a known
 * state before asserting on read behaviour.
 */
@Service
public class ProductIndexService {

    private static final List<String> BRANDS = List.of("Acme", "Globex", "Initech", "Umbrella", "Soylent");

    private final ElasticsearchOperations operations;
    private final EsLabProperties properties;

    public ProductIndexService(ElasticsearchOperations operations, EsLabProperties properties) {
        this.operations = operations;
        this.properties = properties;
    }

    private IndexOperations indexOps() {
        return operations.indexOps(ProductDoc.class);
    }

    /** Drops and recreates the index with the mapping derived from {@link ProductDoc}. */
    public void recreateIndex() {
        deleteIndexIfPresent();
        indexOps().createWithMapping();
    }

    public boolean indexExists() {
        return indexOps().exists();
    }

    public void deleteIndexIfPresent() {
        IndexOperations ops = indexOps();
        if (ops.exists()) {
            ops.delete();
        }
    }

    /**
     * Forces a refresh so just-written documents become searchable. ES is near-real-time:
     * without this, a read issued right after a write legitimately misses data.
     */
    public void refresh() {
        indexOps().refresh();
    }

    public ProductDoc save(ProductDoc doc) {
        return operations.save(doc);
    }

    public Iterable<ProductDoc> saveAll(Collection<ProductDoc> docs) {
        return operations.save(docs);
    }

    public long count() {
        NativeQuery all = NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build();
        return operations.count(all, ProductDoc.class);
    }

    /**
     * Bulk-seeds {@code total} synthetic documents in batches of
     * {@link EsLabProperties#bulkSize()}, returning per-batch latency so indexing throughput
     * can be compared across versions and mapping choices.
     *
     * <p>Caller decides when to make the data visible: nothing here refreshes.
     */
    public List<Duration> bulkSeed(int total) {
        List<Duration> perBatch = new ArrayList<>();
        int batchSize = properties.bulkSize();

        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<IndexQuery> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batch.add(new IndexQueryBuilder()
                        .withId("seed-" + i)
                        .withObject(syntheticProduct(i))
                        .build());
            }
            long startNanos = System.nanoTime();
            operations.bulkIndex(batch, IndexCoordinates.of(properties.indexName()));
            perBatch.add(Duration.ofNanos(System.nanoTime() - startNanos));
        }
        return perBatch;
    }

    private ProductDoc syntheticProduct(int i) {
        return new ProductDoc(
                "seed-" + i,
                "Wireless Bluetooth Headphones Model " + i,
                BRANDS.get(i % BRANDS.size()),
                String.format("B0%06d", i),
                BigDecimal.valueOf(i % 500 + 0.99),
                Instant.now());
    }
}
