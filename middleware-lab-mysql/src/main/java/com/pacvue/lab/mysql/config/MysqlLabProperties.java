package com.pacvue.lab.mysql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param table     table the app seeds and queries
 * @param batchSize rows per JDBC batch when seeding
 * @param seedSize  default row count for {@code POST /api/mysql/seed}
 */
@ConfigurationProperties(prefix = "lab.mysql")
public record MysqlLabProperties(String table, int batchSize, int seedSize) {

    public MysqlLabProperties {
        if (table == null || table.isBlank()) {
            table = "lab_order";
        }
        if (batchSize <= 0) {
            batchSize = 500;
        }
        if (seedSize <= 0) {
            seedSize = 100_000;
        }
    }
}
