package com.pacvue.lab.es.service;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import com.pacvue.lab.es.domain.ProductDoc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

/** Read path. Each method pins down one behaviour the lab wants to verify. */
@Service
public class ProductSearchService {

    private final ElasticsearchOperations operations;

    public ProductSearchService(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    /** Full-text match against the analysed {@code title} field. */
    public SearchHits<ProductDoc> matchTitle(String text, Pageable pageable) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("title").query(text)))
                .withPageable(pageable)
                .build();
        return operations.search(query, ProductDoc.class);
    }

    /** Exact term against the non-analysed {@code brand} keyword field. */
    public SearchHits<ProductDoc> termByBrand(String brand, Pageable pageable) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.term(t -> t.field("brand").value(brand)))
                .withPageable(pageable)
                .build();
        return operations.search(query, ProductDoc.class);
    }

    /**
     * Document count per brand, via a terms aggregation.
     *
     * <p>Aggregates on the {@code brand} keyword field: a terms aggregation reads doc values,
     * which a {@code text} field does not have.
     *
     * <p>{@code size} caps how many buckets come back, and with several shards the counts are
     * approximate - each shard reports its own top N first. {@code shard_size} trades work for
     * accuracy; {@code doc_count_error_upper_bound} in the response says how far off it could be.
     */
    public Map<String, Long> countByBrand(int size) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.matchAll(m -> m))
                .withAggregation("by_brand", Aggregation.of(a -> a.terms(t -> t.field("brand").size(size))))
                .withMaxResults(0)
                .build();

        SearchHits<ProductDoc> hits = operations.search(query, ProductDoc.class);
        ElasticsearchAggregations aggregations = (ElasticsearchAggregations) hits.getAggregations();
        if (aggregations == null) {
            return Map.of();
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        aggregations.get("by_brand").aggregation().getAggregate().sterms().buckets().array()
                .forEach(bucket -> counts.put(bucket.key().stringValue(), bucket.docCount()));
        return counts;
    }

    /**
     * Walks the whole index with {@code search_after}, {@code pageSize} documents at a time.
     *
     * <p>Why not from/size: every shard has to collect {@code from + size} hits and the
     * coordinating node sorts all of them, so cost grows with the offset - and
     * {@code index.max_result_window} (default 10000) cuts it off anyway. A cursor has no such
     * problem because each page starts from the previous page's last sort value.
     *
     * <p>The sort needs a unique tiebreaker or pages can repeat/skip rows. {@code _id} cannot
     * be used for that in ES 8 (fielddata on _id is disallowed), hence {@code asin}.
     */
    public List<ProductDoc> pageAll(int pageSize) {
        List<ProductDoc> all = new ArrayList<>();
        List<Object> cursor = null;

        while (true) {
            NativeQueryBuilder builder = NativeQuery.builder()
                    .withQuery(q -> q.matchAll(m -> m))
                    .withSort(s -> s.field(f -> f.field("updatedAt").order(SortOrder.Asc)))
                    .withSort(s -> s.field(f -> f.field("asin").order(SortOrder.Asc)))
                    .withMaxResults(pageSize);
            if (cursor != null) {
                builder.withSearchAfter(cursor);
            }

            SearchHits<ProductDoc> page = operations.search(builder.build(), ProductDoc.class);
            if (!page.hasSearchHits()) {
                return all;
            }
            page.getSearchHits().forEach(hit -> all.add(hit.getContent()));
            cursor = page.getSearchHit(page.getSearchHits().size() - 1).getSortValues();
        }
    }
}
