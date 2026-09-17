package com.pacvue.lab.es.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lab-specific knobs. Connection settings themselves stay on {@code spring.elasticsearch.*}
 * so Boot's own auto-configuration keeps owning the client.
 *
 * @param indexName  index the sample document is mapped to
 * @param bulkSize   documents per bulk request in the seeding helpers
 */
@ConfigurationProperties(prefix = "lab.es")
public record EsLabProperties(String indexName, int bulkSize) {

    public EsLabProperties {
        indexName = (indexName == null || indexName.isBlank()) ? "lab-product" : indexName;
        bulkSize = bulkSize <= 0 ? 500 : bulkSize;
    }
}
