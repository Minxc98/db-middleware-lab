package com.pacvue.lab.es.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pacvue.lab.common.metrics.Timings;
import com.pacvue.lab.common.probe.MiddlewareProbe;
import com.pacvue.lab.common.probe.ProbeResult;
import org.springframework.stereotype.Service;

/** {@link MiddlewareProbe} backed by the Elasticsearch {@code GET /} info call. */
@Service
public class EsProbeService implements MiddlewareProbe {

    public static final String MIDDLEWARE = "elasticsearch";

    private final ElasticsearchClient client;

    public EsProbeService(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public String middleware() {
        return MIDDLEWARE;
    }

    @Override
    public ProbeResult ping() {
        try {
            var timed = Timings.measure(() -> client.info().version().number());
            return ProbeResult.up(MIDDLEWARE, "version=" + timed.value(), timed.cost());
        } catch (Timings.TimedExecutionException e) {
            return ProbeResult.down(MIDDLEWARE, e.getCause(), e.cost());
        }
    }
}
