package com.pacvue.lab.mysql.api;

import com.pacvue.lab.common.probe.ProbeResult;
import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.repository.OrderJdbcRepository;
import com.pacvue.lab.mysql.service.ExplainService;
import com.pacvue.lab.mysql.service.MysqlProbeService;
import com.pacvue.lab.mysql.service.OrderSeedService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Enough HTTP to drive the lab by hand while a container is up. */
@RestController
@RequestMapping("/api/mysql")
public class MysqlLabController {

    private final MysqlProbeService probe;
    private final OrderSeedService seeder;
    private final OrderJdbcRepository repository;
    private final ExplainService explain;

    public MysqlLabController(MysqlProbeService probe,
                              OrderSeedService seeder,
                              OrderJdbcRepository repository,
                              ExplainService explain) {
        this.probe = probe;
        this.seeder = seeder;
        this.repository = repository;
        this.explain = explain;
    }

    @GetMapping("/ping")
    public ProbeResult ping() {
        return probe.ping();
    }

    /** Drops and recreates the table, then inserts {@code rows} rows. */
    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(required = false) Integer rows) {
        int total = rows == null ? seeder.defaultSeedSize() : rows;
        seeder.recreateTable();
        List<Duration> batches = seeder.seed(total);
        Duration elapsed = batches.stream().reduce(Duration.ZERO, Duration::plus);
        return Map.of(
                "table", repository.table(),
                "rows", repository.count(),
                "batches", batches.size(),
                "elapsedMillis", elapsed.toMillis());
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("table", repository.table(), "rows", repository.count());
    }

    /** {@code GET /api/mysql/explain?sql=SELECT ...} - tabular EXPLAIN of any read query. */
    @GetMapping("/explain")
    public List<ExplainRow> explain(@RequestParam String sql) {
        return explain.explain(sql);
    }

    /** Offset paging vs bookmark paging, side by side (notes 08 §4.1). */
    @GetMapping("/page")
    public Map<String, Object> page(@RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) Long afterId) {
        long start = System.nanoTime();
        var rows = afterId == null
                ? repository.pageByOffset(offset, size)
                : repository.pageAfter(afterId, size);
        return Map.of(
                "mode", afterId == null ? "offset" : "bookmark",
                "returned", rows.size(),
                "elapsedMillis", (System.nanoTime() - start) / 1_000_000,
                "rows", rows);
    }
}
