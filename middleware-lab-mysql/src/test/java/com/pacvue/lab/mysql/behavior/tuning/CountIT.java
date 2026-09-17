package com.pacvue.lab.mysql.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 08 §4.2 - counting rows in InnoDB, where there is no stored row count and every
 * {@code COUNT(*)} is a scan of something.
 */
class CountIT extends AbstractBehaviorIT {

    private static final int ROWS = 10_000;

    @Override
    protected String table() {
        return "lab_tuning_count";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(ROWS);
        // Every tenth row has no remark, which is where COUNT(column) parts company with COUNT(*).
        jdbc.update("UPDATE " + table() + " SET remark = NULL WHERE id % 10 = 0");
        analyze();
    }

    @Test
    @DisplayName("COUNT(*) scans the smallest index it can, not the table")
    void countStarUsesTheSmallestIndex() {
        ExplainRow plan = explain.explainFirst("SELECT COUNT(*) FROM " + table());

        // Any index has one entry per row, so the optimizer picks the narrowest one and reads
        // that. It is still a full scan - just of the cheapest thing available.
        assertThat(plan.type()).isEqualTo("index");
        assertThat(plan.key())
                .as("chose %s", plan.key())
                .isNotEqualTo("PRIMARY");
        assertThat(plan.isCoveringIndex()).isTrue();
    }

    @Test
    @DisplayName("COUNT(*), COUNT(1) and COUNT(pk) agree; COUNT(column) does not")
    void countVariantsDifferOnlyInNullHandling() {
        long countStar = count("COUNT(*)");
        long countOne = count("COUNT(1)");
        long countPk = count("COUNT(id)");
        long countNullable = count("COUNT(remark)");

        assertThat(countStar).isEqualTo(ROWS);
        assertThat(countOne).isEqualTo(ROWS);
        // id is NOT NULL, so counting it counts every row too.
        assertThat(countPk).isEqualTo(ROWS);

        // remark is nullable and COUNT(column) counts non-null values. This is a difference in
        // meaning, not in speed - and the reason "use COUNT(1), it is faster" is bad advice
        // twice over: it is not faster, and the variant that is different is a different query.
        assertThat(countNullable).isEqualTo(ROWS - ROWS / 10);
    }

    @Test
    @DisplayName("the row count in EXPLAIN is an estimate - useful, and not a count")
    void explainRowsIsAnEstimate() {
        long estimate = explain.explainFirst("SELECT * FROM " + table()).rows();
        long exact = count("COUNT(*)");

        assertThat(exact).isEqualTo(ROWS);
        // Sampled from the index statistics, so it is in the right neighbourhood and rarely
        // exact. When an approximate total is good enough - "about 10,000 results" - this costs
        // nothing, while COUNT(*) costs a scan.
        assertThat(estimate).isBetween((long) (ROWS * 0.5), (long) (ROWS * 1.5));
    }

    @Test
    @DisplayName("a filtered count still has to walk the matching rows")
    void filteredCountWalksTheMatches() {
        ExplainRow plan = explain.explainFirst(
                "SELECT COUNT(*) FROM " + table() + " WHERE user_id = 7");

        // Covered by the composite index, so no row lookups - but still one index entry read
        // per matching row. There is no shortcut; if this needs to be O(1), it has to be
        // maintained somewhere else, which is what a counter table or Redis counter is.
        assertThat(plan.key()).isEqualTo("idx_user_status_created");
        assertThat(plan.isCoveringIndex()).isTrue();
        assertThat(count("COUNT(*)", "WHERE user_id = 7")).isEqualTo(ROWS / 50);
    }

    private long count(String expression) {
        return count(expression, "");
    }

    private long count(String expression, String where) {
        Long value = jdbc.queryForObject(
                "SELECT " + expression + " FROM " + table() + " " + where, Long.class);
        return value == null ? -1 : value;
    }
}
