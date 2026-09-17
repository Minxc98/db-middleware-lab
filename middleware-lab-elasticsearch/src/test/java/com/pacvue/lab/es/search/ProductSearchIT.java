package com.pacvue.lab.es.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.domain.ProductDoc;
import com.pacvue.lab.es.service.ProductSearchService;
import com.pacvue.lab.es.support.AbstractElasticsearchIT;
import com.pacvue.lab.es.support.ProductDocs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.Map;
import org.springframework.data.elasticsearch.core.SearchHits;

/** Query semantics: analysed text vs keyword, scoring, paging, aggregations. */
class ProductSearchIT extends AbstractElasticsearchIT {

    @Autowired
    private ProductSearchService searchService;

    @BeforeEach
    void seed() {
        indexService.saveAll(ProductDocs.sample());
        indexService.refresh();
    }

    @Test
    @DisplayName("match on an analysed field hits every document containing the token")
    void matchOnAnalysedField() {
        SearchHits<ProductDoc> hits = searchService.matchTitle("bluetooth", PageRequest.of(0, 10));

        assertThat(hits.getTotalHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("term on a keyword field requires an exact value")
    void termOnKeywordField() {
        assertThat(searchService.termByBrand("Acme", PageRequest.of(0, 10)).getTotalHits()).isEqualTo(2);
        // Keyword fields are not analysed, so case matters.
        assertThat(searchService.termByBrand("acme", PageRequest.of(0, 10)).getTotalHits()).isZero();
    }

    @Test
    @DisplayName("countByBrand aggregates on the keyword field")
    void countsByBrand() {
        Map<String, Long> counts = searchService.countByBrand(10);

        assertThat(counts).containsEntry("Acme", 2L).containsEntry("Globex", 1L);
    }

    @Test
    @DisplayName("pageAll walks the whole index by cursor, in pages")
    void pagesThroughEverythingByCursor() {
        indexService.saveAll(java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new ProductDoc("extra-" + i, "extra product " + i, "Initech",
                        String.format("B0X%05d", i), new java.math.BigDecimal("1.00"),
                        java.time.Instant.parse("2026-01-01T00:00:00Z")))
                .toList());
        indexService.refresh();

        List<ProductDoc> all = searchService.pageAll(10);

        assertThat(all).hasSize(28); // 3 from seed() + 25 here
        assertThat(all).extracting(ProductDoc::getId).doesNotHaveDuplicates();
    }

    // The underlying Elasticsearch behaviour (aggregation accuracy, deep-paging limits) is
    // pinned down separately in behavior/query/AggregationIT and behavior/query/PaginationIT.
}
