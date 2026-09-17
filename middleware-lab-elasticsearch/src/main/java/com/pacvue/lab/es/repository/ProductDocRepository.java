package com.pacvue.lab.es.repository;

import com.pacvue.lab.es.domain.ProductDoc;
import java.util.List;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * Derived-query surface. Useful for checking how Spring Data translates method names into
 * Elasticsearch queries compared with hand-written {@code NativeQuery}.
 */
public interface ProductDocRepository extends ElasticsearchRepository<ProductDoc, String> {

    List<ProductDoc> findByBrand(String brand);

    List<ProductDoc> findByTitleContaining(String fragment);
}
