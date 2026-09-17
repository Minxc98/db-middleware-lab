package com.pacvue.lab.mysql;

import com.pacvue.lab.mysql.config.MysqlLabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MySQL / InnoDB behaviour lab.
 *
 * <p>The application itself is thin on purpose - a probe endpoint and a seeder. The actual
 * subject matter lives in {@code src/test}: index shapes, MVCC visibility, lock modes,
 * redo/undo/binlog, EXPLAIN and replication, each asserted against a real server.
 */
@SpringBootApplication
@EnableConfigurationProperties(MysqlLabProperties.class)
public class MysqlLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(MysqlLabApplication.class, args);
    }
}
