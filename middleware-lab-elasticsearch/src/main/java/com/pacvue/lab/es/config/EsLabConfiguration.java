package com.pacvue.lab.es.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Placeholder for lab-owned beans. The {@code ElasticsearchClient} /
 * {@code ElasticsearchOperations} beans come from Boot's auto-configuration; override them
 * here only when a test needs non-default transport behaviour (custom SSL, sniffing,
 * request timeouts per call, ...).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EsLabProperties.class)
public class EsLabConfiguration {
}
