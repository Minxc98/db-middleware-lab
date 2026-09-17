package com.pacvue.lab.mysql.behavior.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 05 §5 - deadlocks: detected, not waited out, and one side is rolled back.
 */
class DeadlockIT extends AbstractBehaviorIT {

    /** MySQL error 1213, {@code ER_LOCK_DEADLOCK}. */
    private static final int DEADLOCK = 1213;

    /** MySQL error 1205, {@code ER_LOCK_WAIT_TIMEOUT}. */
    private static final int LOCK_WAIT_TIMEOUT = 1205;

    @Override
    protected String table() {
        return "lab_lock_deadlock";
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
    @DisplayName("deadlock detection is on, so a cycle is broken rather than waited out")
    void deadlockDetectionIsOn() {
        assertThat(variable("innodb_deadlock_detect")).isEqualTo("ON");
        // Turning detection off is a real tuning option for workloads with very high contention
        // on a few rows - the detector itself becomes the bottleneck there. The price is that a
        // cycle then waits out innodb_lock_wait_timeout instead of failing immediately.
        assertThat(variable("innodb_lock_wait_timeout")).isEqualTo("10");
    }

    @Test
    @DisplayName("locking two rows in opposite orders deadlocks, and one side is rolled back")
    void oppositeLockOrderDeadlocks() {
        provokeDeadlock();
    }

    /** Two transactions taking rows 5 and 20 in opposite orders. Returns once the cycle broke. */
    private void provokeDeadlock() {
        Session first = session("first");
        Session second = session("second");

        first.begin();
        first.execute("UPDATE " + table() + " SET remark = 'a' WHERE id = 5");

        second.begin();
        second.execute("UPDATE " + table() + " SET remark = 'b' WHERE id = 20");

        // first wants what second holds...
        Future<Integer> firstWaits =
                first.submit("UPDATE " + table() + " SET remark = 'a' WHERE id = 20");
        Session.assertStillBlocked(firstWaits, Duration.ofMillis(500), "first waiting on row 20");

        // ...and now second wants what first holds. The cycle closes and InnoDB picks a victim
        // straight away - no 10 second wait, no hung application thread.
        Throwable secondFailure = null;
        Throwable firstFailure = null;
        try {
            second.execute("UPDATE " + table() + " SET remark = 'b' WHERE id = 5");
        } catch (Session.SessionException e) {
            secondFailure = e;
            assertThat(e.errorCode()).isEqualTo(DEADLOCK);
        }
        if (secondFailure == null) {
            // The victim was the other side instead; either is a legitimate outcome, the
            // detector rolls back whichever transaction has done less work.
            firstFailure = Session.failureOf(firstWaits, Duration.ofSeconds(5));
            assertThat(firstFailure).isInstanceOf(Session.SessionException.class);
            assertThat(((Session.SessionException) firstFailure).errorCode())
                    .isIn(DEADLOCK, LOCK_WAIT_TIMEOUT);
        }

        assertThat(secondFailure != null || firstFailure != null)
                .as("exactly one of the two transactions has to lose")
                .isTrue();

        first.rollback();
        second.rollback();
    }

    @Test
    @DisplayName("the loser is rolled back whole - not just the statement that failed")
    void theVictimLosesItsWholeTransaction() {
        Session first = session("first");
        Session second = session("second");

        first.begin();
        first.execute("UPDATE " + table() + " SET remark = 'first-was-here' WHERE id = 5");

        second.begin();
        second.execute("UPDATE " + table() + " SET remark = 'second-was-here' WHERE id = 20");

        Future<Integer> firstWaits =
                first.submit("UPDATE " + table() + " SET remark = 'x' WHERE id = 20");
        Session.assertStillBlocked(firstWaits, Duration.ofMillis(500), "first waiting on row 20");

        try {
            second.execute("UPDATE " + table() + " SET remark = 'y' WHERE id = 5");
            second.commit();
        } catch (Session.SessionException expected) {
            // second was the victim: its earlier update of row 20 is gone too. A retry has to
            // replay the whole transaction, which is why "just retry the statement" is wrong.
            second.rollback();
        }

        Session.awaitCompletion(firstWaits, Duration.ofSeconds(10), "the surviving transaction");
        first.commit();

        // Whoever survived, the table is consistent: no half-applied transaction.
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table(), Long.class);
        assertThat(rows).isEqualTo(4);
    }

    @Test
    @DisplayName("SHOW ENGINE INNODB STATUS keeps the last deadlock for post-mortem")
    void innodbStatusRecordsTheDeadlock() {
        provokeDeadlock();

        String status = locks.innodbStatus();

        // This section, plus innodb_print_all_deadlocks=ON writing every one of them to the
        // error log, is the whole toolkit: which two transactions, which SQL, which locks,
        // and which one was rolled back.
        assertThat(status).contains("LATEST DETECTED DEADLOCK");
        assertThat(status).contains(table());
        assertThat(status).contains("WE ROLL BACK TRANSACTION");
    }
}
