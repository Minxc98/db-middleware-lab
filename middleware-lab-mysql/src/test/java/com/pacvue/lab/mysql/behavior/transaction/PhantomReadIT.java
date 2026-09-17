package com.pacvue.lab.mysql.behavior.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 04 §5 - InnoDB's RR deals with phantoms twice, because there are two ways to meet one.
 *
 * <ul>
 *   <li>snapshot read: the ReadView simply does not contain rows committed later;</li>
 *   <li>current read: next-key locks stop the row being inserted in the first place.</li>
 * </ul>
 *
 * <p>And there is a documented seam between the two, which the last test walks into on purpose.
 */
class PhantomReadIT extends AbstractBehaviorIT {

    private static final long USER = 7L;

    @Override
    protected String table() {
        return "lab_tx_phantom";
    }

    @BeforeEach
    void seed() {
        // ids 10 and 20, both user_id 7; the gap between them is where the phantom goes.
        jdbc.update("INSERT INTO " + table()
                + " (id, order_no, user_id, status, amount, remark, created_at)"
                + " VALUES (10, 'NO-10', 7, 1, 10.00, 'a', '2026-01-01 00:10:00'),"
                + "        (20, 'NO-20', 7, 1, 20.00, 'b', '2026-01-01 00:20:00')");
    }

    @Test
    @DisplayName("a snapshot read never sees a phantom")
    void snapshotReadHasNoPhantoms() {
        Session writer = session("writer");
        Session reader = session("reader");

        reader.begin();
        assertThat(countSeenBy(reader)).isEqualTo(2);

        writer.begin();
        insertRow(writer, 15);
        writer.commit();

        // The new row's trx_id is not in the reader's ReadView, so it is invisible - no lock
        // was needed to achieve that.
        assertThat(countSeenBy(reader)).isEqualTo(2);
        reader.commit();
    }

    @Test
    @DisplayName("a current read over a range locks the gaps, so the phantom cannot be created")
    void currentReadLocksTheGaps() {
        Session reader = session("reader");
        Session writer = session("writer");

        reader.begin();
        reader.query("SELECT * FROM " + table() + " WHERE user_id = ? FOR UPDATE", USER);

        // Now try to insert a row that would join that range.
        Future<Integer> blocked = writer.submit("INSERT INTO " + table()
                + " (id, order_no, user_id, status, amount, remark, created_at)"
                + " VALUES (15, 'NO-15', 7, 1, 15.00, 'c', '2026-01-01 00:15:00')");

        Session.assertStillBlocked(blocked, Duration.ofSeconds(1),
                "insert into a range somebody else holds next-key locks on");

        reader.commit();
        // Released, and only now does the row appear.
        Session.awaitCompletion(blocked, Duration.ofSeconds(5), "the blocked insert");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table() + " WHERE user_id = ?", Long.class, USER))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("READ COMMITTED has no gap locks, so the phantom gets in")
    void readCommittedLetsThePhantomThrough() {
        Session reader = session("reader").isolation("READ COMMITTED");
        Session writer = session("writer");

        reader.begin();
        assertThat(reader.count(
                "SELECT COUNT(*) FROM " + table() + " WHERE user_id = ? FOR UPDATE", USER))
                .isEqualTo(2);

        // Under RC the locks cover the matching rows and nothing between them, so this goes
        // straight through instead of waiting.
        writer.begin();
        insertRow(writer, 15);
        writer.commit();

        assertThat(reader.count(
                "SELECT COUNT(*) FROM " + table() + " WHERE user_id = ? FOR UPDATE", USER))
                .as("phantom row under READ COMMITTED")
                .isEqualTo(3);
        reader.commit();
    }

    @Test
    @DisplayName("the seam: a row you could not see becomes visible once you update it")
    void updatingAnInvisibleRowMakesItVisible() {
        Session reader = session("reader");
        Session writer = session("writer");

        reader.begin();
        assertThat(countSeenBy(reader)).isEqualTo(2);   // ReadView taken here, no locks

        writer.begin();
        insertRow(writer, 15);
        writer.commit();

        // Still invisible to the snapshot...
        assertThat(countSeenBy(reader)).isEqualTo(2);

        // ...but an UPDATE is a current read: it finds all three rows and stamps every one of
        // them with this transaction's trx_id.
        int updated = reader.update(
                "UPDATE " + table() + " SET status = 9 WHERE user_id = ?", USER);
        assertThat(updated).isEqualTo(3);

        // And a version carrying your own trx_id is visible to you by definition. The row the
        // transaction was never supposed to see is now in its result set.
        assertThat(countSeenBy(reader))
                .as("the residual phantom the notes call out")
                .isEqualTo(3);

        reader.rollback();
    }

    private long countSeenBy(Session session) {
        return session.count("SELECT COUNT(*) FROM " + table() + " WHERE user_id = ?", USER);
    }

    private void insertRow(Session session, long id) {
        session.execute("INSERT INTO " + table()
                        + " (id, order_no, user_id, status, amount, remark, created_at)"
                        + " VALUES (?, ?, 7, 1, ?, 'c', '2026-01-01 00:15:00')",
                id, "NO-" + id, java.math.BigDecimal.valueOf(id));
    }
}
