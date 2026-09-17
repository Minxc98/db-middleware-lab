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
 * Notes 05 §2 - intention locks: the table-level flag that makes "is any row in this table
 * locked?" an O(1) question instead of a scan.
 */
class IntentionLockIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_lock_intention";
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
    @DisplayName("every row lock is announced by a table-level intention lock")
    void rowLockComesWithAnIntentionLock() {
        Session session = session("locker");
        session.begin();
        session.query("SELECT * FROM " + table() + " WHERE id = 10 FOR UPDATE");

        List<LockInspector.Lock> all = locks.locksOf(session, table());

        // Two entries for one statement: the row, and the declaration on the table that some
        // row in it is exclusively locked.
        assertThat(all).anySatisfy(lock -> {
            assertThat(lock.type()).isEqualTo("TABLE");
            assertThat(lock.mode()).isEqualTo("IX");
            assertThat(lock.index()).isNull();
        });
        assertThat(all).anySatisfy(lock -> assertThat(lock.type()).isEqualTo("RECORD"));
    }

    @Test
    @DisplayName("a shared read takes IS, not IX")
    void sharedReadTakesAnIntentionSharedLock() {
        Session session = session("reader");
        session.begin();
        session.query("SELECT * FROM " + table() + " WHERE id = 10 FOR SHARE");

        assertThat(locks.locksOf(session, table()))
                .filteredOn(lock -> "TABLE".equals(lock.type()))
                .singleElement()
                .satisfies(lock -> assertThat(lock.mode()).isEqualTo("IS"));
    }

    @Test
    @DisplayName("intention locks do not conflict with each other")
    void intentionLocksAreMutuallyCompatible() {
        Session first = session("first");
        Session second = session("second");

        first.begin();
        first.query("SELECT * FROM " + table() + " WHERE id = 10 FOR UPDATE");

        second.begin();
        second.query("SELECT * FROM " + table() + " WHERE id = 20 FOR UPDATE");

        // Both hold IX on the same table at the same time. If they conflicted, row-level locking
        // would be pointless - every writer would serialise on the table flag.
        assertThat(tableLockModeOf(first)).isEqualTo("IX");
        assertThat(tableLockModeOf(second)).isEqualTo("IX");
        assertThat(locks.locksOf(first, table()))
                .allSatisfy(lock -> assertThat(lock.status()).isEqualTo("GRANTED"));
        assertThat(locks.locksOf(second, table()))
                .allSatisfy(lock -> assertThat(lock.status()).isEqualTo("GRANTED"));
    }

    @Test
    @DisplayName("what IX is for: a table lock can refuse in O(1) without looking at a row")
    void tableLockConflictsWithTheIntentionLock() {
        Session rowLocker = session("row-locker");
        Session tableLocker = session("table-locker");

        rowLocker.begin();
        rowLocker.query("SELECT * FROM " + table() + " WHERE id = 10 FOR UPDATE");

        // LOCK TABLES ... WRITE wants the table exclusively. It does not have to examine four
        // rows - or four million - to find out it cannot have it: the IX flag is right there.
        Future<Integer> blocked = tableLocker.submit("LOCK TABLES " + table() + " WRITE");
        Session.assertStillBlocked(blocked, Duration.ofSeconds(1),
                "LOCK TABLES WRITE while a row is locked");

        rowLocker.rollback();
        Session.awaitCompletion(blocked, Duration.ofSeconds(5), "the table lock");
        tableLocker.execute("UNLOCK TABLES");
    }

    private String tableLockModeOf(Session session) {
        return locks.locksOf(session, table()).stream()
                .filter(lock -> "TABLE".equals(lock.type()))
                .map(LockInspector.Lock::mode)
                .findFirst()
                .orElse(null);
    }
}
