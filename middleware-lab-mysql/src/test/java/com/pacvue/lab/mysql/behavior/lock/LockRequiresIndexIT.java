package com.pacvue.lab.mysql.behavior.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.LockInspector;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 05 §3 - "row locks are taken on the index", and its consequence: a statement that does
 * not use an index locks every row it reads, which is every row there is.
 *
 * <p>This is the single most expensive misunderstanding in the chapter. It does not look like a
 * table lock in any diagnostic; it looks like a lot of row locks.
 */
class LockRequiresIndexIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_lock_index";
    }

    @Override
    protected void createTable() {
        createLockTable();
    }

    @BeforeEach
    void seed() {
        insertLockRows(5, 10, 15, 20);
    }

    @Test
    @DisplayName("a predicate on an unindexed column locks the entire table, row by row")
    void withoutAnIndexEverythingIsLocked() {
        Session session = session("locker");
        session.begin();
        // remark has no index, so this is a full scan - and a scan under FOR UPDATE locks
        // everything it touches, whether or not it matches.
        session.query("SELECT * FROM " + table() + " WHERE remark = 'row-15' FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // Four rows plus the supremum, every one of them a next-key lock: the table and every
        // gap in it. Nothing can be inserted, updated or deleted anywhere until this commits.
        assertThat(rowLocks).hasSize(5);
        assertThat(rowLocks).allSatisfy(lock -> {
            assertThat(lock.index()).isEqualTo("PRIMARY");
            assertThat(lock.isNextKey()).isTrue();
        });
        assertThat(rowLocks).extracting(LockInspector.Lock::data)
                .containsExactlyInAnyOrder("5", "10", "15", "20", "supremum pseudo-record");
    }

    @Test
    @DisplayName("the same query against an indexed column locks one row")
    void withAnIndexOnlyTheMatchRowIsLocked() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT * FROM " + table() + " WHERE order_no = 'NO-15' FOR UPDATE");

        assertThat(locks.lockedRows(session, table()))
                .as("one index entry and the row behind it")
                .hasSize(2);
    }

    @Test
    @DisplayName("an unindexed predicate blocks a write to an unrelated row")
    void anUnindexedPredicateBlocksUnrelatedWrites() {
        Session locker = session("locker");
        Session other = session("other");

        locker.begin();
        locker.query("SELECT * FROM " + table() + " WHERE remark = 'row-15' FOR UPDATE");

        // Row 5 has nothing to do with the predicate. It is locked anyway.
        Future<Integer> blocked =
                other.submit("UPDATE " + table() + " SET remark = 'x' WHERE id = 5");
        Session.assertStillBlocked(blocked, Duration.ofSeconds(1),
                "update of an unrelated row during an unindexed FOR UPDATE");

        locker.rollback();
        Session.awaitCompletion(blocked, Duration.ofSeconds(5), "the blocked update");
    }

    @Test
    @DisplayName("lock shape follows the execution plan, not the WHERE clause")
    void lockShapeFollowsThePlan() {
        Session session = session("locker");
        session.begin();
        // A predicate on the primary key - but only id is selected, so idx_user (which carries
        // the primary key in its leaves) covers the query, and the optimizer prefers it on a
        // table this small. The locks then land on idx_user, not on PRIMARY.
        session.query("SELECT id FROM " + table() + " WHERE id >= 10 AND id < 16 FOR UPDATE");

        List<String> indexes = locks.rowLocksOf(session, table()).stream()
                .map(LockInspector.Lock::index).distinct().toList();

        assertThat(indexes)
                .as("locks landed on: %s", indexes)
                .contains("idx_user");

        // Which means a lock diagnosis that starts from the WHERE clause can be looking at the
        // wrong index entirely. Start from EXPLAIN.
        assertThat(explain.explainFirst(
                "SELECT id FROM " + table() + " WHERE id >= 10 AND id < 16 FOR UPDATE").key())
                .isEqualTo("idx_user");
    }
}
