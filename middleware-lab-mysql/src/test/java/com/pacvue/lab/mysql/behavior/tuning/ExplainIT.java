package com.pacvue.lab.mysql.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 08 §3 - reading {@code EXPLAIN}: the access-type ladder and the {@code Extra} values
 * worth reacting to.
 */
class ExplainIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_tuning_explain";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(10_000);
        analyze();
    }

    @Test
    @DisplayName("the access-type ladder, from const down to ALL")
    void accessTypeLadder() {
        // const: unique index, constant value, at most one row - read once, before the plan runs.
        assertThat(explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE id = 42").type()).isEqualTo("const");

        // ref: non-unique index, equality - many rows may match.
        assertThat(explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7").type()).isEqualTo("ref");

        // range: an interval of one index.
        assertThat(explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE id BETWEEN 100 AND 200").type())
                .isEqualTo("range");

        // index: the whole index, start to finish. Cheaper than ALL only because the index is
        // smaller than the table - it is still a full scan.
        assertThat(explain.explainFirst(
                "SELECT user_id, status, created_at FROM " + table()).type()).isEqualTo("index");

        // ALL: the whole table.
        assertThat(explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE remark = 'row 1'").type()).isEqualTo("ALL");
    }

    @Test
    @DisplayName("eq_ref is what a join to a unique key looks like")
    void joinToAUniqueKeyIsEqRef() {
        List<ExplainRow> plan = explain.explain(
                "SELECT a.* FROM " + table() + " a JOIN " + table() + " b ON b.id = a.id"
                        + " WHERE a.user_id = 7");

        // One row of the driving table, one lookup in the other, guaranteed at most one hit.
        // The best a join can do.
        assertThat(plan).anySatisfy(row -> assertThat(row.type()).isEqualTo("eq_ref"));
        // And the optimizer drives from the smaller result - the indexed predicate side.
        assertThat(plan.get(0).key()).isEqualTo("idx_user_status_created");
    }

    @Test
    @DisplayName("Using filesort means the sort could not come from an index")
    void filesortAppearsWhenOrderByCannotUseAnIndex() {
        ExplainRow sorted = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7 ORDER BY amount");
        ExplainRow indexOrder = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7 ORDER BY status, created_at");

        // amount is not in any index, so the rows have to be collected and sorted.
        assertThat(sorted.hasExtra("Using filesort")).isTrue();
        // status and created_at follow user_id in the composite index, so the index already
        // delivers them in order and there is nothing to sort.
        assertThat(indexOrder.hasExtra("Using filesort"))
                .as("Extra was: %s", indexOrder.extra())
                .isFalse();
    }

    @Test
    @DisplayName("Using temporary means an intermediate result had to be materialised")
    void temporaryTableAppearsForUngroupableGroupBy() {
        ExplainRow grouped = explain.explainFirst(
                "SELECT remark, COUNT(*) FROM " + table() + " GROUP BY remark");
        ExplainRow groupedOnIndex = explain.explainFirst(
                "SELECT user_id, COUNT(*) FROM " + table() + " GROUP BY user_id");

        // remark has no index, so the groups have to be accumulated in a temporary table.
        assertThat(grouped.hasExtra("Using temporary")).isTrue();
        // user_id is the leading column of an index, so the groups arrive already adjacent.
        assertThat(groupedOnIndex.hasExtra("Using temporary"))
                .as("Extra was: %s", groupedOnIndex.extra())
                .isFalse();
    }

    @Test
    @DisplayName("EXPLAIN estimates; EXPLAIN ANALYZE measures - and it runs the statement")
    void explainAnalyzeReportsWhatActuallyHappened() {
        ExplainRow estimate = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7");
        String actual = explain.explainAnalyze(
                "SELECT * FROM " + table() + " WHERE user_id = 7");

        // The estimate is drawn from sampled statistics and can be wrong by a lot; that is the
        // usual reason a plan is bad in production and fine in staging.
        assertThat(estimate.rows()).isPositive();

        // EXPLAIN ANALYZE executes the query and reports real timings and real row counts.
        // Which also means never pointing it at a statement with side effects.
        assertThat(actual).contains("actual time=");
        assertThat(actual).contains("rows=");
    }

    @Test
    @DisplayName("FORMAT=JSON carries the cost numbers the table drops")
    void jsonFormatCarriesCosts() {
        String json = explain.explainJson("SELECT * FROM " + table() + " WHERE user_id = 7");

        // The optimizer chooses by cost, and this is the only place it shows its arithmetic.
        // Comparing the costs of two candidate plans beats arguing about which looks better.
        assertThat(json).contains("query_cost");
        assertThat(json).contains("rows_examined_per_scan");
        assertThat(json).contains("idx_user_status_created");
    }
}
