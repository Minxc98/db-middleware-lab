package com.pacvue.lab.es.support;

import com.pacvue.lab.es.service.ProductIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for every Elasticsearch IT: points the Boot context at whatever
 * {@link ElasticsearchContainerFactory} resolves (managed container or external node) and
 * gives each test a freshly created index.
 *
 * <p>The index is {@code lab-product-it} (see {@code application-test.yml}), so pointing the
 * suite at a long-lived node does not touch the data in {@code lab-product}.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractElasticsearchIT {

    @Autowired
    protected ProductIndexService indexService;

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ElasticsearchContainerFactory::httpUri);
    }

    @BeforeEach
    void resetIndex() {
        indexService.recreateIndex();
    }
}
