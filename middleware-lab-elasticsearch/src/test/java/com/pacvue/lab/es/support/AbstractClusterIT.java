package com.pacvue.lab.es.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the real 3-node cluster:
 *
 * <pre>
 * cd middleware-lab-elasticsearch
 * docker compose -f docker-compose.cluster.yml up -d
 * </pre>
 *
 * <p>Nodes are tagged {@code node.attr.tier} = hot (es01) / warm (es02) / cold (es03), which
 * is what the allocation-filtering tests steer on.
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiresThreeNodeCluster
public abstract class AbstractClusterIT {

    @Autowired
    protected ElasticsearchOperations operations;

    @Autowired
    protected ElasticsearchClient client;

    protected abstract String indexName();

    protected IndexCoordinates index() {
        return IndexCoordinates.of(indexName());
    }

    protected IndexOperations indexOps() {
        return operations.indexOps(index());
    }

    @DynamicPropertySource
    static void clusterProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ClusterAvailability::clusterUri);
    }

    protected void dropIndexIfPresent() {
        IndexOperations ops = indexOps();
        if (ops.exists()) {
            ops.delete();
        }
    }
}
