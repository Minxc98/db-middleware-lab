package com.pacvue.lab.mysql.behavior.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 03 §4 - the leftmost prefix rule on {@code idx_user_status_created (user_id, status,
 * created_at)}.
 *
 * <p>The rule is usually recited as "does it use the index or not", which is too coarse. The
 * honest measure is {@code key_len}: how many <em>bytes</em> of the index the predicate actually
 * reached. For this index the columns cost
 *
 * <pre>
 *   user_id     BIGINT      NOT NULL -> 8
 *   status      TINYINT     NOT NULL -> 1
 *   created_at  DATETIME(3) NOT NULL -> 7   (5 for the datetime + 2 for 3 digits of fraction)
 * </pre>
 *
 * so a predicate that reaches all three shows key_len 16, and one that stops at the first shows 8.
 */
class LeftmostPrefixIT extends AbstractBehaviorIT {

    private static final int USER_ID_BYTES = 8;
    private static final int USER_AND_STATUS_BYTES = 9;
    private static final int ALL_THREE_BYTES = 16;

    private static final String A_TIMESTAMP = "2026-01-01 00:07:00";

    @Override
    protected String table() {
        return "lab_idx_prefix";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(10_000);
        analyze();
    }

    @Test
    @DisplayName("the leading column alone reaches 8 bytes of the index")
    void leadingColumnOnly() {
        ExplainRow plan = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7");

        assertThat(plan.type()).isEqualTo("ref");
        assertThat(plan.key()).isEqualTo("idx_user_status_created");
        assertThat(plan.keyLen()).isEqualTo(USER_ID_BYTES);
    }

    @Test
    @DisplayName("two columns reach 9 bytes, three reach 16")
    void prefixGrowsColumnByColumn() {
        ExplainRow two = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE user_id = 7 AND status = 1");
        ExplainRow three = explain.explainFirst(
                "SELECT * FROM " + table()
                        + " WHERE user_id = 7 AND status = 1 AND created_at = '" + A_TIMESTAMP + "'");

        assertThat(two.keyLen()).isEqualTo(USER_AND_STATUS_BYTES);
        assertThat(three.keyLen()).isEqualTo(ALL_THREE_BYTES);
        // More of the index used means fewer index entries to walk.
        assertThat(three.rows()).isLessThanOrEqualTo(two.rows());
    }

    @Test
    @DisplayName("skipping the middle column stops the prefix - but ICP still filters on the third")
    void skippingAColumnTruncatesThePrefix() {
        ExplainRow plan = explain.explainFirst(
                "SELECT * FROM " + table()
                        + " WHERE user_id = 7 AND created_at = '" + A_TIMESTAMP + "'");

        // status is missing, so the index can only be positioned by user_id: 8 bytes, not 16.
        assertThat(plan.keyLen()).isEqualTo(USER_ID_BYTES);
        // The created_at predicate is not wasted though - it is pushed down to the engine and
        // applied while walking the index, which is what "Using index condition" means.
        // Leftmost prefix decides how the index is *positioned*; ICP decides what is *filtered*
        // before going back to the clustered index. The two are routinely confused.
        assertThat(plan.hasExtra("Using index condition")).isTrue();
        assertThat(plan.filtered()).isLessThan(100.0);
    }

    @Test
    @DisplayName("without the leading column the index cannot be used at all")
    void withoutTheLeadingColumnTheIndexIsUseless() {
        ExplainRow plan = explain.explainFirst(
                "SELECT * FROM " + table() + " WHERE status = 1");

        // The index is sorted by user_id first, so rows with status=1 are scattered all over it.
        // There is no range to seek to, and a full scan of the table is cheaper than a full scan
        // of the index plus a lookup per row.
        assertThat(plan.isFullTableScan()).isTrue();
        assertThat(plan.key()).isNull();
    }

    @Test
    @DisplayName("a range in the middle stops everything after it")
    void everythingAfterARangeIsLost() {
        ExplainRow plan = explain.explainFirst(
                "SELECT * FROM " + table()
                        + " WHERE user_id = 7 AND status > 1 AND created_at = '" + A_TIMESTAMP + "'");

        assertThat(plan.type()).isEqualTo("range");
        // 9 bytes: user_id and status. Inside "status > 1" the created_at values are not sorted,
        // so created_at cannot narrow the seek - only filter afterwards.
        assertThat(plan.keyLen()).isEqualTo(USER_AND_STATUS_BYTES);
        assertThat(plan.hasExtra("Using index condition")).isTrue();
    }
}
