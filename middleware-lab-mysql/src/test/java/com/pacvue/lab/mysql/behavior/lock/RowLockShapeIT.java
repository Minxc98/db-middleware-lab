package com.pacvue.lab.mysql.behavior.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.LockInspector;
import com.pacvue.lab.mysql.support.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 05 §3/§4 - the three row-lock shapes, read out of
 * {@code performance_schema.data_locks} rather than reasoned about.
 *
 * <p>Rows sit at primary keys 5, 10, 15, 20, so the gaps are
 * {@code (-inf,5) (5,10) (10,15) (15,20) (20,+inf)}.
 */
class RowLockShapeIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_lock_shape";
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
    @DisplayName("unique index, equality, row exists -> plain record lock")
    void uniqueEqualityHitDegeneratesToARecordLock() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT id FROM " + table() + " WHERE id = 10 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // One row, no gap. There is no phantom to prevent: a unique index cannot grow a second
        // row with id = 10, so locking the space around it would only cost concurrency.
        assertThat(rowLocks).singleElement().satisfies(lock -> {
            assertThat(lock.index()).isEqualTo("PRIMARY");
            assertThat(lock.mode()).isEqualTo("X,REC_NOT_GAP");
            assertThat(lock.data()).isEqualTo("10");
        });
    }

    @Test
    @DisplayName("unique index, equality, row missing -> gap lock on the gap it would land in")
    void uniqueEqualityMissDegeneratesToAGapLock() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT id FROM " + table() + " WHERE id = 12 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // Nothing to lock, so InnoDB locks the absence: the gap (10,15), named after the record
        // that follows it. Now nobody else can create id = 12 either, which is what makes
        // "SELECT ... FOR UPDATE then INSERT if missing" safe.
        assertThat(rowLocks).singleElement().satisfies(lock -> {
            assertThat(lock.mode()).isEqualTo("X,GAP");
            assertThat(lock.data()).isEqualTo("15");
        });
    }

    @Test
    @DisplayName("non-unique index, equality -> next-key lock plus the gap after it")
    void nonUniqueEqualityKeepsTheGaps() {
        Session session = session("locker");
        session.begin();
        // user_id = id * 10, so this is the row with id 15.
        session.query("SELECT id FROM " + table() + " WHERE user_id = 150 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // Three locks, and each one has a job:
        //   idx_user  X            on (150,15)  next-key: the entry and the gap before it
        //   idx_user  X,GAP        on (200,20)  the gap after it - another user_id=150 could
        //                                       be inserted there, so it has to be held too
        //   PRIMARY   X,REC_NOT_GAP on 15       the row itself, reached through the index
        assertThat(rowLocks).hasSize(3);
        assertThat(rowLocks).anySatisfy(lock -> {
            assertThat(lock.index()).isEqualTo("idx_user");
            assertThat(lock.isNextKey()).isTrue();
            assertThat(lock.data()).isEqualTo("150, 15");
        });
        assertThat(rowLocks).anySatisfy(lock -> {
            assertThat(lock.index()).isEqualTo("idx_user");
            assertThat(lock.isGapOnly()).isTrue();
            assertThat(lock.data()).isEqualTo("200, 20");
        });
        assertThat(rowLocks).anySatisfy(lock -> {
            assertThat(lock.index()).isEqualTo("PRIMARY");
            assertThat(lock.isRecordOnly()).isTrue();
            assertThat(lock.data()).isEqualTo("15");
        });
    }

    @Test
    @DisplayName("unique index, equality on the secondary key -> record locks on both indexes")
    void uniqueSecondaryEqualityLocksBothIndexes() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT id FROM " + table() + " WHERE order_no = 'NO-15' FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // The secondary entry and the row it points at. Both, because another transaction could
        // reach the same row from either direction.
        assertThat(rowLocks).hasSize(2);
        assertThat(rowLocks).allSatisfy(lock -> assertThat(lock.isRecordOnly()).isTrue());
        assertThat(rowLocks).extracting(LockInspector.Lock::index)
                .containsExactlyInAnyOrder("uk_order_no", "PRIMARY");
    }

    @Test
    @DisplayName("a range takes a next-key lock on every entry it walks past")
    void rangeTakesNextKeyLocks() {
        Session session = session("locker");
        session.begin();
        // FORCE INDEX: with a tiny table the optimizer may cover this query from a secondary
        // index instead, and then the locks land there. That is its own lesson (see
        // LockRequiresIndexIT) but it is not this one.
        session.query("SELECT * FROM " + table() + " FORCE INDEX (PRIMARY)"
                + " WHERE id >= 10 AND id < 16 FOR UPDATE");

        List<LockInspector.Lock> rowLocks = locks.rowLocksOf(session, table());

        // Rows 10 and 15 plus the gaps that lead to them - and 20 as well, because the scan has
        // to read it to find out that the range is over. The lock on 20 is the one people are
        // surprised by: a row outside the range, locked by a range that stops before it.
        assertThat(rowLocks).extracting(LockInspector.Lock::data)
                .contains("10", "15");
        assertThat(rowLocks).anySatisfy(lock -> assertThat(lock.isNextKey()).isTrue());
    }

    @Test
    @DisplayName("a range with no upper bound locks the supremum - the gap that never ends")
    void openEndedRangeLocksTheSupremum() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT * FROM " + table() + " FORCE INDEX (PRIMARY)"
                + " WHERE id > 15 FOR UPDATE");

        assertThat(locks.lockedRows(session, table()))
                .as("the pseudo-record standing for 'everything after the last row'")
                .contains("supremum pseudo-record");
    }
}
