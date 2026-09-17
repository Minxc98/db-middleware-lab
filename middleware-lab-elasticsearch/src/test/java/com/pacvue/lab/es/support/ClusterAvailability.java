package com.pacvue.lab.es.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Detects the 3-node cluster from {@code docker-compose.cluster.yml}.
 *
 * <p>Used as a JUnit {@code @EnabledIf} condition so the multi-node suite disables itself
 * cleanly when the cluster is not running, instead of failing the build - the condition is
 * evaluated before Spring even builds a context.
 */
public final class ClusterAvailability {

    /** Where the 3-node cluster listens (es01). */
    public static final String CLUSTER_URI_PROPERTY = "lab.es.cluster.uri";
    public static final String DEFAULT_CLUSTER_URI = "http://localhost:9201";

    private static final int REQUIRED_NODES = 3;

    private ClusterAvailability() {
    }

    public static String clusterUri() {
        String configured = System.getProperty(CLUSTER_URI_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("LAB_ES_CLUSTER_URI");
        }
        return (configured == null || configured.isBlank()) ? DEFAULT_CLUSTER_URI : configured.trim();
    }

    /** JUnit condition: true when a cluster with at least 3 nodes answers. */
    public static boolean isThreeNodeClusterUp() {
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(800))
                    .build()
                    .send(HttpRequest.newBuilder(URI.create(clusterUri() + "/_cluster/health"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            // Cheap enough to parse by hand; avoids dragging a JSON mapper into a condition.
            int marker = response.body().indexOf("\"number_of_nodes\"");
            if (marker < 0) {
                return false;
            }
            String tail = response.body().substring(marker).replaceAll("[^0-9]+", " ").trim();
            int nodes = Integer.parseInt(tail.split(" ")[0]);
            return nodes >= REQUIRED_NODES;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
