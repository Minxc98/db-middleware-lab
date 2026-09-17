package com.pacvue.lab.es.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.es.support.AbstractElasticsearchIT;
import com.pacvue.lab.es.support.ProductDocs;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Index creation, mapping and the near-real-time write/read contract. */
class IndexLifecycleIT extends AbstractElasticsearchIT {

    @Test
    @DisplayName("recreateIndex leaves an empty index in place")
    void recreatesIndex() {
        assertThat(indexService.indexExists()).isTrue();
        assertThat(indexService.count()).isZero();
    }

    @Test
    @DisplayName("documents are only counted after a refresh")
    void writesBecomeVisibleAfterRefresh() {
        indexService.saveAll(ProductDocs.sample());
        indexService.refresh();

        assertThat(indexService.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("bulkSeed writes in batches and reports per-batch latency")
    void bulkSeedReportsPerBatchTiming() {
        List<Duration> perBatch = indexService.bulkSeed(450);
        indexService.refresh();

        // bulk-size is 200 in application-test.yml, so 450 documents means 3 batches.
        assertThat(perBatch).hasSize(3);
        assertThat(perBatch).allSatisfy(d -> assertThat(d).isPositive());
        assertThat(indexService.count()).isEqualTo(450);
    }

    // Mapping details, alias rollover and the rest of the index-level behaviour are covered
    // by the behaviour suite: see behavior/mapping and behavior/tuning/RolloverIT.
}
