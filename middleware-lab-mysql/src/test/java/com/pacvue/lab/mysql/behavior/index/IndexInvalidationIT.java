package com.pacvue.lab.mysql.behavior.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 03 §6 - the ways a perfectly good index stops being used.
 *
 * <p>They all come down to one thing: a B+ tree can only be seeked by the value it is sorted on.
 * Wrap the column in anything - a function, a cast, a leading wildcard - and the sorted order of
 * the raw column no longer tells you where to look.
 */
class IndexInvalidationIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_idx_invalid";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(10_000);
        analyze();
    }

    @Test
    @DisplayName("a function on the column throws the index away")
    void functionOnTheColumn() {
        // A selective prefix on purpose: order_no is 'NO-000000001'..'NO-000010000', so
        // 'NO-000000123' matches one row. Ask for a prefix that matches most of the table and
        // the optimizer will pick a full scan even when it *can* use the index - a different
        // phenomenon, and one that would make this test lie about which one it is showing.
        ExplainRow wrapped = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE LEFT(order_no, 12) = 'NO-000000123'");
        ExplainRow rewritten = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE order_no LIKE 'NO-000000123%'");

        assertThat(wrapped.isFullTableScan()).isTrue();
        assertThat(wrapped.possibleKeys()).as("the optimizer does not even consider it").isNull();

        // The same question asked as a range on the raw column is a seek.
        assertThat(rewritten.type()).isEqualTo("range");
        assertThat(rewritten.key()).isEqualTo("uk_order_no");
    }

    @Test
    @DisplayName("comparing a string column to a number converts the column, not the literal")
    void implicitTypeConversion() {
        ExplainRow converted = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE order_no = 123");

        // MySQL's rule is that a string/number comparison is done as numbers, so it has to
        // evaluate CAST(order_no AS DOUBLE) for every row - a function on the column again,
        // just an invisible one. Nothing in the statement looks wrong, which is what makes this
        // the version of the mistake that reaches production.
        assertThat(converted.isFullTableScan()).isTrue();
        assertThat(converted.key()).isNull();

        // Quote the literal and it is an ordinary lookup. (The value has to exist: on a unique
        // index with no match, EXPLAIN reports "no matching row in const table" and no key,
        // which would look like the same failure for an entirely different reason.)
        assertThat(explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE order_no = 'NO-000000123'").key())
                .isEqualTo("uk_order_no");
    }

    @Test
    @DisplayName("a leading wildcard has nowhere to seek to; a trailing one is a range")
    void leadingWildcard() {
        ExplainRow leading = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE order_no LIKE '%123'");
        ExplainRow trailing = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE order_no LIKE 'NO-000000123%'");

        assertThat(leading.isFullTableScan()).isTrue();
        assertThat(trailing.type()).isEqualTo("range");
    }

    @Test
    @DisplayName("OR with an unindexed column poisons the whole predicate")
    void orWithAnUnindexedColumn() {
        ExplainRow both = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7 OR remark = 'row 1'");

        // Either side of the OR can produce rows, and remark has no index, so every row has to
        // be examined anyway. Half a plan is no plan.
        assertThat(both.isFullTableScan()).isTrue();

        // The usual rewrite - UNION - lets each branch use its own access path. Here the second
        // branch still has nothing to use, which is the honest answer: index remark, or accept
        // the scan. What UNION does buy is that the first branch stops being dragged down.
        var union = explain.explain(
                "SELECT * FROM " + table() + " WHERE user_id = 7"
                        + " UNION SELECT * FROM " + table() + " WHERE remark = 'row 1'");
        assertThat(union.get(0).key()).isEqualTo("idx_user_status_created");
    }

    @Test
    @DisplayName("8.0 can index the expression itself - a functional index")
    void functionalIndexRescuesTheFunctionCase() {
        // The 5.7 answer to "WHERE LEFT(order_no, 6) = ..." was "add a generated column and index
        // that". 8.0.13+ indexes the expression directly.
        jdbc.execute("ALTER TABLE " + table()
                + " ADD INDEX idx_order_prefix ((LEFT(order_no, 12)))");
        analyze();

        ExplainRow plan = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE LEFT(order_no, 12) = 'NO-000000123'");

        assertThat(plan.isFullTableScan())
                .as("with a functional index the same predicate is a seek; Extra: %s", plan.extra())
                .isFalse();
        assertThat(plan.key()).isEqualTo("idx_order_prefix");
    }
}
