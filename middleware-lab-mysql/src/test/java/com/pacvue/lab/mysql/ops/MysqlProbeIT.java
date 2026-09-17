package com.pacvue.lab.mysql.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.common.probe.ProbeResult;
import com.pacvue.lab.mysql.service.MysqlProbeService;
import com.pacvue.lab.mysql.support.AbstractMysqlIT;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Smoke test: the server answers, the schema is ours, seeding works. */
class MysqlProbeIT extends AbstractMysqlIT {

    @Autowired
    private MysqlProbeService probe;

    @Test
    @DisplayName("probe reports the server version")
    void probeReportsVersion() {
        ProbeResult result = probe.ping();

        assertThat(result.middleware()).isEqualTo("mysql");
        assertThat(result.healthy()).as(result.detail()).isTrue();
        assertThat(result.detail()).startsWith("8.0");
        assertThat(result.cost()).isNotNull();
    }

    @Test
    @DisplayName("seeding writes every row and reports one timing per batch")
    void seedingWritesEveryRow() {
        int total = 1_000;

        List<java.time.Duration> batches = seeder.seed(total);

        assertThat(repository.count()).isEqualTo(total);
        // batch-size is 200 in application-test.yml
        assertThat(batches).hasSize(5);
        assertThat(repository.sumAmount()).isPositive();
    }

    @Test
    @DisplayName("the suite runs against the lab_mysql_it schema, never the app schema")
    void suiteOwnsItsOwnSchema() {
        assertThat(repository.table()).isEqualTo("lab_order_it");
    }
}
