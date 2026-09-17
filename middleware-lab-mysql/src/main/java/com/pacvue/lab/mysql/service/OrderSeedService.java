package com.pacvue.lab.mysql.service;

import com.pacvue.lab.common.metrics.Timings;
import com.pacvue.lab.mysql.config.MysqlLabProperties;
import com.pacvue.lab.mysql.domain.OrderRow;
import com.pacvue.lab.mysql.repository.OrderJdbcRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.springframework.stereotype.Service;

/** Fills the order table with enough rows that index choice and paging actually matter. */
@Service
public class OrderSeedService {

    /** Rows are spread over this many users, so {@code user_id} has realistic selectivity. */
    public static final int USER_COUNT = 200;

    /** Status values 0..STATUS_COUNT-1, so the second index column is low-cardinality. */
    public static final int STATUS_COUNT = 5;

    private final OrderJdbcRepository repository;
    private final MysqlLabProperties properties;

    public OrderSeedService(OrderJdbcRepository repository, MysqlLabProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void recreateTable() {
        repository.recreateTable();
    }

    /**
     * Inserts {@code total} rows in batches of {@link MysqlLabProperties#batchSize()}.
     *
     * @return per-batch wall-clock times, so a caller can see the cost profile rather than
     *         just a total (the first batches are slower - the buffer pool is cold)
     */
    public List<Duration> seed(int total) {
        List<Duration> perBatch = new ArrayList<>();
        int batchSize = properties.batchSize();
        // Fixed seed: two runs of the suite produce byte-identical data, so a test that
        // depends on a particular row landing in a particular page stays reproducible.
        Random random = new Random(42);
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);

        for (int start = 0; start < total; start += batchSize) {
            int end = Math.min(start + batchSize, total);
            List<OrderRow> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                batch.add(new OrderRow(
                        null,
                        "NO-%09d".formatted(i),
                        i % USER_COUNT,
                        random.nextInt(STATUS_COUNT),
                        BigDecimal.valueOf(random.nextDouble() * 1000).setScale(2, RoundingMode.HALF_UP),
                        "seeded row " + i,
                        base.plusMinutes(i)));
            }
            perBatch.add(Timings.measure(() -> repository.insertBatch(batch)).cost());
        }
        return perBatch;
    }

    public int defaultSeedSize() {
        return properties.seedSize();
    }
}
