package com.pacvue.lab.es.config;

import org.springframework.stereotype.Component;

/**
 * Resolves index names for {@code @Document} annotations.
 *
 * <p>{@code @Document(indexName = ...)} understands SpEL ({@code #{...}}), not property
 * placeholders ({@code ${...}}) - an unresolved placeholder ends up in the URL verbatim and
 * Elasticsearch rejects it with a 400. Going through a bean keeps the name configurable and
 * lets tests point at their own index.
 */
@Component("indexNames")
public class IndexNames {

    private final EsLabProperties properties;

    public IndexNames(EsLabProperties properties) {
        this.properties = properties;
    }

    public String product() {
        return properties.indexName();
    }
}
