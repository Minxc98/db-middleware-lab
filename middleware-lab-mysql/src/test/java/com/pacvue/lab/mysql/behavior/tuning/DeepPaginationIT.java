package com.pacvue.lab.mysql.behavior.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 08 §4.1 - deep pagination.
 *
 * <p>{@code LIMIT 9000, 10} does not skip 9000 rows. It reads them and throws them away, and the
 * cost is measured here in {@code Handler_read_next}: the number of times the server asked the
 * storage engine for "the next row". Unlike wall-clock time that number does not depend on cache
 * state, so it is something a test can assert on.
 */
class DeepPaginationIT extends AbstractBehaviorIT {

    private static final int ROWS = 10_000;
    private static final int PAGE = 10;
    private static final int DEEP_OFFSET = 9_000;

    @Override
    protected String table() {
        return "lab_tuning_paging";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(ROWS);
        analyze();
    }

    @Test
    @DisplayName("an offset page reads everything it skips; a bookmark page does not")
    void offsetPagingReadsWhatItSkips() {
        Session session = session("pager");

        session.flushStatus();
        List<Map<String, Object>> byOffset = session.query(
                "SELECT * FROM " + table() + " ORDER BY id LIMIT " + DEEP_OFFSET + ", " + PAGE);
        long offsetReads = session.status("Handler_read_next");

        session.flushStatus();
        List<Map<String, Object>> byBookmark = session.query(
                "SELECT * FROM " + table() + " WHERE id > " + DEEP_OFFSET + " ORDER BY id LIMIT " + PAGE);
        long bookmarkReads = session.status("Handler_read_next");

        // Same ten rows, either way.
        assertThat(byOffset).hasSize(PAGE);
        assertThat(byBookmark).hasSize(PAGE);
        assertThat(byOffset.get(0).get("id")).isEqualTo(byBookmark.get(0).get("id"));

        // Wildly different amounts of work: about 9010 rows against about 10.
        assertThat(offsetReads).isGreaterThan(DEEP_OFFSET);
        assertThat(bookmarkReads).isLessThan(PAGE * 3L);
        assertThat(offsetReads / Math.max(bookmarkReads, 1))
                .as("offset read %s rows, bookmark read %s", offsetReads, bookmarkReads)
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("the cost of an offset page grows with the offset; a bookmark page is flat")
    void offsetCostGrowsWithDepth() {
        Session session = session("pager");

        long shallow = rowsReadFor(session,
                "SELECT * FROM " + table() + " ORDER BY id LIMIT 10, " + PAGE);
        long deep = rowsReadFor(session,
                "SELECT * FROM " + table() + " ORDER BY id LIMIT " + DEEP_OFFSET + ", " + PAGE);

        assertThat(deep).isGreaterThan(shallow * 10);

        long bookmarkShallow = rowsReadFor(session,
                "SELECT * FROM " + table() + " WHERE id > 10 ORDER BY id LIMIT " + PAGE);
        long bookmarkDeep = rowsReadFor(session,
                "SELECT * FROM " + table() + " WHERE id > " + DEEP_OFFSET + " ORDER BY id LIMIT " + PAGE);

        // Page 900 costs exactly what page 1 costs. This is why an API that paginates by cursor
        // scales and one that paginates by page number does not.
        assertThat(bookmarkDeep).isEqualTo(bookmarkShallow);
    }

    @Test
    @DisplayName("deferred join: pay the offset on the index, not on the rows")
    void deferredJoinPaysTheOffsetOnTheIndexOnly() {
        Session session = session("pager");

        // When a bookmark is not available - arbitrary page numbers in a UI, say - the offset
        // still has to be walked. It can at least be walked over a covering index, so the
        // skipped rows are never assembled: only the ten that survive are looked up.
        String deferred = "SELECT o.* FROM " + table() + " o"
                + " JOIN (SELECT id FROM " + table() + " ORDER BY id LIMIT " + DEEP_OFFSET + ", " + PAGE + ") p"
                + " ON p.id = o.id";

        List<Map<String, Object>> rows = session.query(deferred);
        assertThat(rows).hasSize(PAGE);

        // The inner query is covered by the primary key index...
        var inner = explain.explain(deferred);
        assertThat(inner).anySatisfy(plan ->
                assertThat(plan.hasExtra("Using index")).isTrue());
        // ...and the outer one is ten primary-key lookups.
        assertThat(inner).anySatisfy(plan -> assertThat(plan.type()).isEqualTo("eq_ref"));
    }

    private long rowsReadFor(Session session, String sql) {
        session.flushStatus();
        session.query(sql);
        return session.status("Handler_read_next");
    }
}
