package com.pacvue.lab.mysql.service;

import com.pacvue.lab.common.metrics.Timings;
import com.pacvue.lab.common.probe.MiddlewareProbe;
import com.pacvue.lab.common.probe.ProbeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Liveness + version round trip, reported the same way every module reports it. */
@Service
public class MysqlProbeService implements MiddlewareProbe {

    private final JdbcTemplate jdbc;

    public MysqlProbeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String middleware() {
        return "mysql";
    }

    @Override
    public ProbeResult ping() {
        try {
            Timings.Timed<String> timed = Timings.measure(() -> jdbc.queryForObject(
                    "SELECT CONCAT(VERSION(), ' / ', @@innodb_version)", String.class));
            return ProbeResult.up(middleware(), timed.value(), timed.cost());
        } catch (Timings.TimedExecutionException e) {
            return ProbeResult.down(middleware(), e.getCause(), e.cost());
        }
    }
}
