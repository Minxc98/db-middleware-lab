package com.pacvue.lab.es;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for poking at a running Elasticsearch by hand (REST endpoints under /api/es).
 * The automated behaviour checks live in src/test and do not need this application.
 */
@SpringBootApplication
public class ElasticsearchLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElasticsearchLabApplication.class, args);
    }
}
