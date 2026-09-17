package com.pacvue.lab.mysql.behavior.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 04 §4 - two kinds of read living in the same transaction.
 *
 * <pre>
 *   snapshot read   plain SELECT           MVCC, possibly a historical version, no locks
 *   current read    SELECT ... FOR UPDATE  latest committed version, and it takes locks
 *                   SELECT ... FOR SHARE
 *                   INSERT / UPDATE / DELETE
 * </pre>
 *
 * <p>The consequence is the one that bites: {@code UPDATE t SET x = x + 1} does not use the
 * {@code x} your last {@code SELECT} showed you.
 */
class SnapshotVsCurrentReadIT extends AbstractBehaviorIT {

    private static final long ROW = 1L;

    @Override
    protected String table() {
        return "lab_tx_readkind";
    }

    @BeforeEach
    void seed() {
        insertOrders(ROW);
        jdbc.update("UPDATE " + table() + " SET amount = 100.00 WHERE id = ?", ROW);
    }

    @Test
    @DisplayName("in the same transaction, a plain SELECT and FOR UPDATE can disagree")
    void snapshotAndCurrentReadDisagree() {
        Session writer = session("writer");
        Session reader = session("reader");

        reader.begin();
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");   // ReadView taken here

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        writer.commit();

        assertThat(amountSeenBy(reader))
                .as("snapshot read: still the old version")
                .isEqualByComparingTo("100.00");

        BigDecimal locked = reader.queryValue(
                "SELECT amount FROM " + table() + " WHERE id = ? FOR UPDATE", BigDecimal.class, ROW);

        // Current read: MVCC is bypassed entirely. It has to be - you cannot take a lock on a
        // version that no longer exists.
        assertThat(locked)
                .as("current read: the latest committed version")
                .isEqualByComparingTo("200.00");

        reader.commit();
    }

    @Test
    @DisplayName("UPDATE reads the latest version, not the one your SELECT returned")
    void updateIsACurrentReadAndOverwritesFromTheLatestValue() {
        Session writer = session("writer");
        Session reader = session("reader");

        reader.begin();
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        writer.commit();

        // The reader still believes amount is 100. Its own UPDATE does not.
        reader.execute("UPDATE " + table() + " SET amount = amount + 1 WHERE id = ?", ROW);

        assertThat(amountSeenBy(reader))
                .as("201, not 101 - the increment was applied to the version the UPDATE read")
                .isEqualByComparingTo("201.00");

        reader.commit();
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM " + table() + " WHERE id = ?", BigDecimal.class, ROW))
                .isEqualByComparingTo("201.00");
    }

    @Test
    @DisplayName("a snapshot read takes no locks at all")
    void snapshotReadTakesNoLocks() {
        Session reader = session("reader");

        reader.begin();
        reader.query("SELECT * FROM " + table() + " WHERE id = ?", ROW);

        assertThat(locks.rowLocksOf(reader, table()))
                .as("plain SELECT under MVCC")
                .isEmpty();

        reader.query("SELECT * FROM " + table() + " WHERE id = ? FOR SHARE", ROW);

        assertThat(locks.rowLocksOf(reader, table()))
                .as("FOR SHARE is a current read, so now there is one")
                .isNotEmpty();

        reader.commit();
    }

    private BigDecimal amountSeenBy(Session session) {
        return session.queryValue(
                "SELECT amount FROM " + table() + " WHERE id = ?", BigDecimal.class, ROW);
    }
}
