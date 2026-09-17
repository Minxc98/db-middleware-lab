package com.pacvue.lab.mysql.behavior.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 03 §3/§5 - the two {@code Extra} values people mix up:
 *
 * <pre>
 *   Using index            covering index - the query never leaves the secondary index
 *   Using index condition  index condition pushdown - it does leave, just less often
 * </pre>
 */
class CoveringIndexAndIcpIT extends AbstractBehaviorIT {

    private static final String A_TIMESTAMP = "2026-01-01 00:07:00";

    @Override
    protected String table() {
        return "lab_idx_covering";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(10_000);
        analyze();
    }

    @Test
    @DisplayName("a query whose every column is in the index never touches the row")
    void coveringIndexAvoidsTheRowEntirely() {
        ExplainRow covering = explain.explainFirst(
                "SELECT user_id, status, created_at FROM " + table() + " WHERE user_id = 7");

        assertThat(covering.key()).isEqualTo("idx_user_status_created");
        assertThat(covering.isCoveringIndex())
                .as("Extra was: %s", covering.extra())
                .isTrue();
    }

    @Test
    @DisplayName("SELECT * over the same predicate is not covered - one column is enough to break it")
    void oneExtraColumnBreaksCoverage() {
        ExplainRow covering = explain.explainFirst(
                "SELECT user_id, status FROM " + table() + " WHERE user_id = 7");
        ExplainRow notCovering = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7");

        assertThat(covering.isCoveringIndex()).isTrue();
        assertThat(notCovering.isCoveringIndex())
                .as("Extra was: %s", notCovering.extra())
                .isFalse();
        // Both read the same number of index entries. The uncovered one additionally walks the
        // clustered index once per surviving row - the cost SELECT * quietly adds.
        assertThat(notCovering.rows()).isEqualTo(covering.rows());
    }

    @Test
    @DisplayName("ICP applies the un-seekable part of the predicate inside the engine")
    void indexConditionPushdownFiltersBeforeGoingBackForTheRow() {
        String sql = "SELECT * FROM " + table()
                + " WHERE user_id = 7 AND created_at = '" + A_TIMESTAMP + "'";

        ExplainRow withIcp = explain.explainFirst(sql);

        assertThat(withIcp.hasExtra("Using index condition")).isTrue();
        // filtered is the optimizer saying "of the rows this seek returns, about this share will
        // survive" - with ICP, that filtering happens in the engine, per index entry.
        assertThat(withIcp.filtered()).isLessThan(100.0);

        // Turn ICP off for this session and the same plan keeps the same index and the same
        // key_len, but the condition moves up to the server layer: every one of those index
        // entries now costs a lookup into the clustered index first.
        jdbc.execute("SET SESSION optimizer_switch = 'index_condition_pushdown=off'");
        try {
            ExplainRow withoutIcp = explain.explainFirst(sql);

            assertThat(withoutIcp.key()).isEqualTo(withIcp.key());
            assertThat(withoutIcp.keyLen()).isEqualTo(withIcp.keyLen());
            assertThat(withoutIcp.hasExtra("Using index condition")).isFalse();
            assertThat(withoutIcp.hasExtra("Using where")).isTrue();
        } finally {
            jdbc.execute("SET SESSION optimizer_switch = 'index_condition_pushdown=on'");
        }
    }

    @Test
    @DisplayName("EXPLAIN FORMAT=JSON says using_index in as many words")
    void jsonPlanNamesTheOptimisation() {
        String json = explain.explainJson(
                "SELECT user_id, status FROM " + table() + " WHERE user_id = 7");

        assertThat(json).contains("\"using_index\": true");
        // The JSON form is the one worth reading when a plan gets nested: it carries the cost
        // numbers the tabular form drops.
        assertThat(json).contains("query_cost");
    }
}
