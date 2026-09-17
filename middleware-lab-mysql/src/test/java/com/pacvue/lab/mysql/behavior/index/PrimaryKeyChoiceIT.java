package com.pacvue.lab.mysql.behavior.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 03 §7 - why an auto-increment {@code BIGINT} beats a random string primary key.
 *
 * <p>Two claims, both measurable on the same data:
 *
 * <ol>
 *   <li>random inserts land in the middle of the clustered index and split pages, so the same
 *       rows occupy more space;</li>
 *   <li>every secondary index leaf stores a copy of the primary key, so a 36-byte key inflates
 *       every secondary index too.</li>
 * </ol>
 */
class PrimaryKeyChoiceIT extends AbstractBehaviorIT {

    /** Enough rows for page splits to accumulate; small enough to insert in a couple of seconds. */
    private static final int ROWS = 40_000;

    private static final String SEQUENTIAL = "lab_pk_sequential";
    private static final String RANDOM = "lab_pk_random";

    @Override
    protected String table() {
        return SEQUENTIAL;
    }

    @Override
    protected void createTable() {
        // Same payload, same secondary index. The only difference is the primary key.
        jdbc.execute("""
                CREATE TABLE %s (
                    id      BIGINT      NOT NULL AUTO_INCREMENT,
                    payload CHAR(100)   NOT NULL,
                    lookup  VARCHAR(40) NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_lookup (lookup)
                ) ENGINE=InnoDB
                """.formatted(SEQUENTIAL));
        jdbc.execute("DROP TABLE IF EXISTS " + RANDOM);
        jdbc.execute("""
                CREATE TABLE %s (
                    id      CHAR(36)    NOT NULL,
                    payload CHAR(100)   NOT NULL,
                    lookup  VARCHAR(40) NOT NULL,
                    PRIMARY KEY (id),
                    KEY idx_lookup (lookup)
                ) ENGINE=InnoDB
                """.formatted(RANDOM));
    }

    @Test
    @DisplayName("a random primary key costs more space in the clustered index and in every secondary index")
    void randomPrimaryKeyCostsSpaceTwice() {
        Random random = new Random(7);
        List<Object[]> sequential = new ArrayList<>(ROWS);
        List<Object[]> shuffled = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String payload = "p".repeat(100);
            String lookup = "L-%08d".formatted(random.nextInt(ROWS));
            sequential.add(new Object[]{payload, lookup});
            // UUID v4: no order at all, so each insert lands wherever its hash happens to fall.
            shuffled.add(new Object[]{UUID.randomUUID().toString(), payload, lookup});
        }

        jdbc.batchUpdate("INSERT INTO " + SEQUENTIAL + " (payload, lookup) VALUES (?, ?)", sequential);
        jdbc.batchUpdate("INSERT INTO " + RANDOM + " (id, payload, lookup) VALUES (?, ?, ?)", shuffled);
        jdbc.execute("ANALYZE TABLE " + SEQUENTIAL);
        jdbc.execute("ANALYZE TABLE " + RANDOM);

        long sequentialData = sizeOf(SEQUENTIAL, "DATA_LENGTH");
        long randomData = sizeOf(RANDOM, "DATA_LENGTH");
        long sequentialIndex = sizeOf(SEQUENTIAL, "INDEX_LENGTH");
        long randomIndex = sizeOf(RANDOM, "INDEX_LENGTH");

        // (1) Page splits. An auto-increment key always appends to the rightmost page, which
        // fills up and is then left alone. A random key inserts into pages that are already
        // full, splitting them roughly in half - so pages end up around 50-70% used.
        assertThat(randomData)
                .as("clustered index: random %s bytes vs sequential %s", randomData, sequentialData)
                .isGreaterThan(sequentialData);

        // (2) Key width. idx_lookup holds the same values in both tables, but each of its leaf
        // entries also carries the primary key: 8 bytes here, 36 there.
        assertThat(randomIndex)
                .as("secondary index: random %s bytes vs sequential %s", randomIndex, sequentialIndex)
                .isGreaterThan(sequentialIndex);
    }

    @Test
    @DisplayName("the auto-increment counter hands out values in order, which is the whole point")
    void autoIncrementIsMonotonic() {
        jdbc.batchUpdate("INSERT INTO " + SEQUENTIAL + " (payload, lookup) VALUES (?, ?)",
                List.of(new Object[]{"a", "L-1"}, new Object[]{"b", "L-2"}, new Object[]{"c", "L-3"}));

        List<Long> ids = jdbc.queryForList("SELECT id FROM " + SEQUENTIAL + " ORDER BY id", Long.class);

        assertThat(ids).isSorted();
        assertThat(ids.get(ids.size() - 1) - ids.get(0)).isEqualTo(ids.size() - 1);

        // And the next value is visible before it is used - this is the counter a distributed id
        // scheme (snowflake, segment allocation) is replacing when it gives up on AUTO_INCREMENT.
        Long next = jdbc.queryForObject("""
                SELECT AUTO_INCREMENT FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, Long.class, SEQUENTIAL);
        assertThat(next).isEqualTo(ids.get(ids.size() - 1) + 1);
    }

    private long sizeOf(String tableName, String column) {
        Number value = jdbc.queryForObject("""
                SELECT %s FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """.formatted(column), Number.class, tableName);
        return value == null ? 0L : value.longValue();
    }
}
