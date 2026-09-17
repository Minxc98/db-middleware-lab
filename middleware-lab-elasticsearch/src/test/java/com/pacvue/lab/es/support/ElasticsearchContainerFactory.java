package com.pacvue.lab.es.support;

import com.pacvue.lab.common.testsupport.MiddlewareContainers;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Decides which Elasticsearch the integration tests talk to, in this order:
 *
 * <ol>
 *   <li>{@code -Dlab.es.external.uri=...} (or the {@code LAB_ES_URIS} env var) - use exactly that.</li>
 *   <li>A node already answering on {@value #DEFAULT_LOCAL_URI} - reuse it. This is what
 *       {@code docker compose up -d elasticsearch} leaves running, and it means a plain
 *       right-click-run in the IDE works with no run configuration to set up.
 *       Turn it off with {@code -Dlab.es.autodetect=false}.</li>
 *   <li>Otherwise start a container through Testcontainers.</li>
 * </ol>
 *
 * <p>Step 2 exists because Testcontainers needs the <em>test JVM</em> to reach a Docker daemon.
 * When Docker lives inside WSL2 and the IDE runs on Windows, it cannot: {@code /var/run/docker.sock}
 * is a Linux kernel object, and the WSL port forwarding that exposes {@code localhost:9200}
 * does not expose Docker's management API. That combination is what produces
 * "Could not find a valid Docker environment".
 */
public final class ElasticsearchContainerFactory {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchContainerFactory.class);

    /** System property (or {@code LAB_ES_URIS} env var) pointing at an already running node. */
    public static final String EXTERNAL_URIS_PROPERTY = "lab.es.external.uri";

    /** Set to {@code false} to always use Testcontainers, even with a node on localhost. */
    public static final String AUTODETECT_PROPERTY = "lab.es.autodetect";

    /** Where a locally running lab node is expected (see docker-compose.yml). */
    public static final String DEFAULT_LOCAL_URI = "http://localhost:9200";

    /** Used when the filtered properties file is unavailable (e.g. running from an IDE). */
    private static final String DEFAULT_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:8.18.6";

    private ElasticsearchContainerFactory() {
    }

    /** Lazy holder: the container is only built and started if the steps above found nothing. */
    private static final class Holder {
        private static final ElasticsearchContainer INSTANCE = build();

        static {
            INSTANCE.start();
        }
    }

    /** Endpoint the Spring context should use, whichever mode wins. */
    public static String httpUri() {
        String configured = externalUri();
        if (configured != null) {
            log.info("Elasticsearch: using configured endpoint {}", configured);
            return configured;
        }
        String detected = autodetectLocal();
        if (detected != null) {
            log.info("Elasticsearch: reusing the node already running at {} "
                    + "(disable with -D{}=false)", detected, AUTODETECT_PROPERTY);
            return detected;
        }
        log.info("Elasticsearch: no local node found, starting a container");
        return "http://" + Holder.INSTANCE.getHttpHostAddress();
    }

    /** Explicitly configured endpoint, or {@code null}. */
    public static String externalUri() {
        String value = System.getProperty(EXTERNAL_URIS_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv("LAB_ES_URIS");
        }
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * The managed container. Only call this from a test that genuinely needs to drive the
     * container itself (stop it, cut the network, inspect logs) - it fails when the suite is
     * talking to a node it does not own.
     */
    public static ElasticsearchContainer container() {
        if (externalUri() != null || autodetectLocal() != null) {
            throw new IllegalStateException(
                    "Tests are pointed at an Elasticsearch this suite does not manage; "
                            + "re-run with -D" + AUTODETECT_PROPERTY + "=false to force a container.");
        }
        return Holder.INSTANCE;
    }

    /** {@link #DEFAULT_LOCAL_URI} if something that looks like Elasticsearch answers there. */
    private static String autodetectLocal() {
        if (!Boolean.parseBoolean(System.getProperty(AUTODETECT_PROPERTY, "true"))) {
            return null;
        }
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(500))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(DEFAULT_LOCAL_URI))
                                    .timeout(Duration.ofSeconds(1))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            // Make sure it is actually Elasticsearch and not some other service on 9200.
            if (response.statusCode() == 200 && response.body().contains("\"cluster_name\"")) {
                return DEFAULT_LOCAL_URI;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // nothing listening, or not speaking HTTP: fall back to Testcontainers
        }
        return null;
    }

    private static ElasticsearchContainer build() {
        return new ElasticsearchContainer(DockerImageName.parse(resolveImage()))
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withReuse(MiddlewareContainers.reuseEnabled());
    }

    private static String resolveImage() {
        try (InputStream in = ElasticsearchContainerFactory.class
                .getResourceAsStream("/lab-containers.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String image = props.getProperty("elasticsearch.image");
                // An unfiltered placeholder still looks like @elasticsearch.image@.
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
