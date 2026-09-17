package com.pacvue.lab.mysql.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 08 §5 - partitioning: one logical table, several physical ones, and the two things that
 * actually pay for themselves - pruning and {@code DROP PARTITION}.
 *
 * <p>It is a single-server arrangement. It does not add capacity, and it is not sharding.
 */
class PartitionPruningIT extends AbstractBehaviorIT {

    private static final String UNPARTITIONABLE = "lab_tuning_partition_bad";

    @Override
    protected String table() {
        return "lab_tuning_partition";
    }

    @Override
    protected void createTable() {
        jdbc.execute("""
                CREATE TABLE %s (
                    id         BIGINT   NOT NULL AUTO_INCREMENT,
                    created_at DATETIME NOT NULL,
                    user_id    BIGINT   NOT NULL,
                    remark     VARCHAR(50),
                    PRIMARY KEY (id, created_at)
                ) ENGINE=InnoDB
                PARTITION BY RANGE (YEAR(created_at)) (
                    PARTITION p2024 VALUES LESS THAN (2025),
                    PARTITION p2025 VALUES LESS THAN (2026),
                    PARTITION p2026 VALUES LESS THAN (2027),
                    PARTITION pmax  VALUES LESS THAN MAXVALUE
                )
                """.formatted(table()));
        jdbc.execute("DROP TABLE IF EXISTS " + UNPARTITIONABLE);
    }

    @BeforeEach
    void seed() {
        for (int year = 2024; year <= 2026; year++) {
            for (int i = 0; i < 300; i++) {
                jdbc.update("INSERT INTO " + table() + " (created_at, user_id, remark)"
                                + " VALUES (?, ?, ?)",
                        year + "-06-01 00:00:00", i % 50, "row " + i);
            }
        }
        analyze();
    }

    @Test
    @DisplayName("a predicate on the partition key reads one partition")
    void pruningReadsOnePartition() {
        ExplainRow pruned = explain.explainFirst("SELECT * FROM " + table()
                + " WHERE created_at >= '2026-01-01' AND created_at < '2027-01-01'");

        assertThat(pruned.partitions()).isEqualTo("p2026");
        // Note what this is not: an index. It is still a scan - of a quarter of the data.
        // Pruning reduces how much there is to scan; it does not replace indexing.
        assertThat(pruned.isFullTableScan()).isTrue();
    }

    @Test
    @DisplayName("a predicate on anything else reads every partition")
    void withoutThePartitionKeyEveryPartitionIsRead() {
        ExplainRow unpruned = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE remark = 'row 1'");

        assertThat(unpruned.partitions()).isEqualTo("p2024,p2025,p2026,pmax");
        // Which is the trap: partitioning a table whose queries do not carry the partition key
        // makes every query slower, because now it is four scans instead of one.
    }

    @Test
    @DisplayName("dropping a partition is instant; deleting the same rows is not")
    void dropPartitionIsMetadataOnly() {
        assertThat(rowsPerPartition()).containsEntry("p2024", 300L);

        jdbc.execute("ALTER TABLE " + table() + " DROP PARTITION p2024");

        // The partition's tablespace is unlinked. No rows are read, no undo is written, no
        // purge is queued - which is the entire reason to partition an append-only history
        // table by time. A DELETE of 300 rows would have done all three.
        assertThat(rowsPerPartition()).doesNotContainKey("p2024");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Long.class))
                .isEqualTo(600L);
    }

    @Test
    @DisplayName("every unique key has to contain the partition column")
    void uniqueKeysMustContainThePartitionColumn() {
        // The reason is mechanical: uniqueness is enforced per index, each partition has its own
        // index, and nothing checks across partitions. If the partition column is not in the key,
        // the same value could exist once per partition.
        assertThatThrownBy(() -> jdbc.execute("""
                CREATE TABLE %s (
                    id         BIGINT   NOT NULL PRIMARY KEY,
                    created_at DATETIME NOT NULL
                ) ENGINE=InnoDB
                PARTITION BY RANGE (YEAR(created_at)) (PARTITION p0 VALUES LESS THAN (2027))
                """.formatted(UNPARTITIONABLE)))
                .hasStackTraceContaining(
                        "A PRIMARY KEY must include all columns in the table's partitioning function");

        // Which is why this table's primary key is (id, created_at) rather than (id) - a
        // compromise that has to be made before any of the upside is available.
        assertThat(jdbc.queryForList("SHOW KEYS FROM " + table() + " WHERE Key_name = 'PRIMARY'"))
                .extracting(row -> row.get("Column_name"))
                .containsExactly("id", "created_at");
    }

    private Map<String, Long> rowsPerPartition() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT PARTITION_NAME, TABLE_ROWS FROM information_schema.PARTITIONS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, table());
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                row -> String.valueOf(row.get("PARTITION_NAME")),
                row -> ((Number) row.get("TABLE_ROWS")).longValue()));
    }
}
