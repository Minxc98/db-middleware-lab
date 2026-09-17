package com.pacvue.lab.mysql.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * The two-node topology from {@code docker-compose.replication.yml}:
 *
 * <pre>
 * cd middleware-lab-mysql
 * docker compose -f docker-compose.replication.yml up -d
 * </pre>
 *
 * <p>Used as a JUnit {@code @EnabledIf} condition so the replication suite disables itself
 * cleanly when the topology is not running instead of failing the build - the condition is
 * evaluated before any connection is opened.
 */
public final class ReplicationCluster {

    public static final String SOURCE_PORT_PROPERTY = "lab.mysql.source.port";
    public static final String REPLICA_PORT_PROPERTY = "lab.mysql.replica.port";

    public static final int DEFAULT_SOURCE_PORT = 3307;
    public static final int DEFAULT_REPLICA_PORT = 3308;

    private static final String USER = "root";
    private static final String PASSWORD = "labroot";

    private ReplicationCluster() {
    }

    public static String sourceUrl() {
        return url(port(SOURCE_PORT_PROPERTY, DEFAULT_SOURCE_PORT));
    }

    public static String replicaUrl() {
        return url(port(REPLICA_PORT_PROPERTY, DEFAULT_REPLICA_PORT));
    }

    public static Session source(String name) {
        return Session.connect(sourceUrl(), USER, PASSWORD, name);
    }

    public static Session replica(String name) {
        return Session.connect(replicaUrl(), USER, PASSWORD, name);
    }

    /** JUnit condition: true when a replica answers and both of its replication threads run. */
    public static boolean isUp() {
        try (Connection connection = DriverManager.getConnection(replicaUrl(), USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW REPLICA STATUS")) {
            if (!rs.next()) {
                return false;   // answered, but is not a replica of anything
            }
            return "Yes".equals(rs.getString("Replica_IO_Running"))
                    && "Yes".equals(rs.getString("Replica_SQL_Running"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String url(int port) {
        return "jdbc:mysql://localhost:%d/%s?%s"
                .formatted(port, MysqlContainerFactory.IT_DATABASE, MysqlContainerFactory.URL_PARAMS);
    }

    private static int port(String property, int fallback) {
        String configured = System.getProperty(property);
        return (configured == null || configured.isBlank()) ? fallback : Integer.parseInt(configured.trim());
    }
}
