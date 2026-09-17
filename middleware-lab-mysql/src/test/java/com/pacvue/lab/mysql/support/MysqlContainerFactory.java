package com.pacvue.lab.mysql.support;

import com.pacvue.lab.common.testsupport.MiddlewareContainers;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Decides which MySQL the integration tests talk to, in this order:
 *
 * <ol>
 *   <li>{@code -Dlab.mysql.url=...} (or the {@code LAB_MYSQL_URL} env var) - use exactly that.</li>
 *   <li>A lab node already answering on {@value #DEFAULT_LOCAL_HOST}:{@value #DEFAULT_LOCAL_PORT} -
 *       reuse it. This is what {@code docker compose up -d mysql} leaves running, so a plain
 *       right-click-run in the IDE works with no run configuration to set up.
 *       Turn it off with {@code -Dlab.mysql.autodetect=false}.</li>
 *   <li>Otherwise start a container through Testcontainers.</li>
 * </ol>
 *
 * <p>Step 2 exists because Testcontainers needs the <em>test JVM</em> to reach a Docker daemon.
 * When Docker lives inside WSL2 and the IDE runs on Windows it cannot: {@code /var/run/docker.sock}
 * is a Linux kernel object, and the WSL port forwarding that exposes {@code localhost:3306} does
 * not expose Docker's management API. That combination is what produces
 * "Could not find a valid Docker environment".
 *
 * <p>Detection is deliberately strict: it only accepts a server that already has the lab's
 * {@value #IT_DATABASE} schema. An unrelated MySQL on 3306 is left alone rather than having
 * lab tables created in it.
 */
public final class MysqlContainerFactory {

    private static final Logger log = LoggerFactory.getLogger(MysqlContainerFactory.class);

    /** System property (or {@code LAB_MYSQL_URL} env var) pointing at an already running server. */
    public static final String EXTERNAL_URL_PROPERTY = "lab.mysql.url";
    public static final String EXTERNAL_USER_PROPERTY = "lab.mysql.username";
    public static final String EXTERNAL_PASSWORD_PROPERTY = "lab.mysql.password";

    /** Set to {@code false} to always use Testcontainers, even with a server on localhost. */
    public static final String AUTODETECT_PROPERTY = "lab.mysql.autodetect";

    public static final String DEFAULT_LOCAL_HOST = "localhost";
    public static final int DEFAULT_LOCAL_PORT = 3306;

    /** Schema the suite owns. Never {@code lab_mysql} - that one belongs to the app. */
    public static final String IT_DATABASE = "lab_mysql_it";

    static final String DEFAULT_USERNAME = "root";
    static final String DEFAULT_PASSWORD = "labroot";

    /**
     * Connection parameters the whole suite relies on. {@code allowPublicKeyRetrieval} is needed
     * because 8.0 authenticates with caching_sha2_password and this connection is not TLS - fine
     * for a local lab, never do it against a real server.
     */
    static final String URL_PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                    + "&connectTimeout=3000&socketTimeout=60000"
                    // Without this the driver sends one INSERT per row and a 20k-row fixture
                    // takes a minute. With it, a batch becomes one multi-row INSERT.
                    + "&rewriteBatchedStatements=true";

    /** Used when the filtered properties file is unavailable (e.g. running from an IDE). */
    private static final String DEFAULT_IMAGE = "mysql:8.0.46";

    /**
     * mysqld flags the lab depends on, kept in step with {@code docker-compose.yml}.
     * Passed as flags rather than a mounted my.cnf because a config file bind-mounted from a
     * Windows checkout arrives world-writable and MySQL silently ignores it.
     */
    static final List<String> MYSQLD_FLAGS = List.of(
            "--server-id=1",
            "--log-bin=binlog",
            "--binlog-format=ROW",
            "--log-replica-updates=ON",
            "--gtid-mode=ON",
            "--enforce-gtid-consistency=ON",
            "--innodb-flush-log-at-trx-commit=1",
            "--sync-binlog=1",
            "--innodb-buffer-pool-size=256M",
            "--innodb-file-per-table=ON",
            "--innodb-print-all-deadlocks=ON",
            "--transaction-isolation=REPEATABLE-READ",
            "--innodb-lock-wait-timeout=10",
            "--performance-schema=ON",
            // Metadata locks are not instrumented by default; without this
            // performance_schema.metadata_locks is always empty.
            "--performance-schema-instrument=wait/lock/metadata/sql/mdl=ON",
            "--key-buffer-size=16M",
            "--character-set-server=utf8mb4",
            "--collation-server=utf8mb4_0900_ai_ci");

    private MysqlContainerFactory() {
    }

    /** Lazy holder: the container is only built and started if the steps above found nothing. */
    private static final class Holder {
        @SuppressWarnings("resource") // deliberately outlives every test in the JVM
        private static final MySQLContainer<?> INSTANCE = build();

        static {
            INSTANCE.start();
        }
    }

    /** JDBC URL the Spring context should use, whichever mode wins. */
    public static String jdbcUrl() {
        String configured = externalUrl();
        if (configured != null) {
            log.info("MySQL: using configured endpoint {}", configured);
            return configured;
        }
        String detected = autodetectLocal();
        if (detected != null) {
            log.info("MySQL: reusing the server already running at {}:{} (disable with -D{}=false)",
                    DEFAULT_LOCAL_HOST, DEFAULT_LOCAL_PORT, AUTODETECT_PROPERTY);
            return detected;
        }
        log.info("MySQL: no local lab server found, starting a container");
        return Holder.INSTANCE.getJdbcUrl();
    }

    public static String username() {
        if (externalUrl() != null) {
            return System.getProperty(EXTERNAL_USER_PROPERTY, DEFAULT_USERNAME);
        }
        return autodetectLocal() != null ? DEFAULT_USERNAME : Holder.INSTANCE.getUsername();
    }

    public static String password() {
        if (externalUrl() != null) {
            return System.getProperty(EXTERNAL_PASSWORD_PROPERTY, DEFAULT_PASSWORD);
        }
        return autodetectLocal() != null ? DEFAULT_PASSWORD : Holder.INSTANCE.getPassword();
    }

    /** Explicitly configured URL, or {@code null}. */
    public static String externalUrl() {
        String value = System.getProperty(EXTERNAL_URL_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv("LAB_MYSQL_URL");
        }
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The managed container. Only call this from a test that genuinely needs to drive the
     * container itself (stop it, inspect its files) - it fails when the suite is talking to a
     * server it does not own.
     */
    public static MySQLContainer<?> container() {
        if (externalUrl() != null || autodetectLocal() != null) {
            throw new IllegalStateException(
                    "Tests are pointed at a MySQL this suite does not manage; re-run with -D"
                            + AUTODETECT_PROPERTY + "=false to force a container.");
        }
        return Holder.INSTANCE;
    }

    /** The local lab URL if a server with the lab schema answers there, otherwise {@code null}. */
    private static String autodetectLocal() {
        if (!Boolean.parseBoolean(System.getProperty(AUTODETECT_PROPERTY, "true"))) {
            return null;
        }
        return LocalProbe.RESULT;
    }

    /** Probing costs a TCP connect; do it once per JVM rather than on every accessor call. */
    private static final class LocalProbe {
        private static final String RESULT = probe();

        private static String probe() {
            String url = "jdbc:mysql://%s:%d/%s?%s"
                    .formatted(DEFAULT_LOCAL_HOST, DEFAULT_LOCAL_PORT, IT_DATABASE, URL_PARAMS);
            try (Connection connection = DriverManager.getConnection(url, DEFAULT_USERNAME, DEFAULT_PASSWORD);
                 Statement statement = connection.createStatement();
                 // Connecting to IT_DATABASE already proves the lab schema exists; this proves the
                 // account can read the tables the lock and log suites depend on.
                 ResultSet rs = statement.executeQuery(
                         "SELECT COUNT(*) FROM performance_schema.data_locks")) {
                return rs.next() ? url : null;
            } catch (Exception e) {
                // nothing listening, wrong credentials, or no lab schema: fall through
                return null;
            }
        }
    }

    private static MySQLContainer<?> build() {
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse(resolveImage()))
                .withDatabaseName(IT_DATABASE)
                .withUsername(DEFAULT_USERNAME)
                .withPassword(DEFAULT_PASSWORD)
                .withReuse(MiddlewareContainers.reuseEnabled());
        container.setCommand(MYSQLD_FLAGS.toArray(String[]::new));
        container.withUrlParam("useSSL", "false");
        container.withUrlParam("allowPublicKeyRetrieval", "true");
        container.withUrlParam("serverTimezone", "UTC");
        return container;
    }

    private static String resolveImage() {
        try (InputStream in = MysqlContainerFactory.class
                .getResourceAsStream("/lab-containers.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String image = props.getProperty("mysql.image");
                // An unfiltered placeholder still looks like @mysql.image@.
                if (image != null && !image.isBlank() && !image.startsWith("@")) {
                    return image;
                }
            }
        } catch (IOException ignored) {
            // fall through to the default
        }
        return DEFAULT_IMAGE;
    }
}
