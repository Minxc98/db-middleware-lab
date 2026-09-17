package com.pacvue.lab.es.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.common.probe.ProbeResult;
import com.pacvue.lab.es.service.EsProbeService;
import com.pacvue.lab.es.support.AbstractElasticsearchIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Smoke test: the container is up and the client talks to it. */
class EsProbeIT extends AbstractElasticsearchIT {

    @Autowired
    private EsProbeService probeService;

    @Test
    @DisplayName("ping reports the cluster version")
    void pingsCluster() {
        ProbeResult result = probeService.ping();

        assertThat(result.healthy()).isTrue();
        assertThat(result.middleware()).isEqualTo("elasticsearch");
        assertThat(result.detail()).startsWith("version=8.");
        assertThat(result.cost()).isNotNull();
    }
}
