package com.pacvue.lab.mysql.behavior.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 04 §2 - the three concurrency problems and which isolation level stops which.
 *
 * <p>Each test is the same shape: one session changes a row, another reads it at a chosen
 * isolation level, and the assertion is about what the reader is allowed to see.
 */
class IsolationLevelIT extends AbstractBehaviorIT {

    private static final long ROW = 1L;

    @Override
    protected String table() {
        return "lab_tx_isolation";
    }

    @BeforeEach
    void seed() {
        insertOrders(ROW);
        jdbc.update("UPDATE " + table() + " SET amount = 100.00 WHERE id = ?", ROW);
    }

    @Test
    @DisplayName("the server default is REPEATABLE READ")
    void defaultIsRepeatableRead() {
        Session reader = session("reader");

        // MySQL's default, and not the SQL standard's (that is READ COMMITTED). Historically it
        // was needed for statement-based replication to be correct; it stayed.
        assertThat(reader.isolation()).isEqualTo("REPEATABLE-READ");
    }

    @Test
    @DisplayName("READ UNCOMMITTED sees a change that may still be rolled back")
    void readUncommittedSeesDirtyData() {
        Session writer = session("writer");
        Session reader = session("reader").isolation("READ UNCOMMITTED");

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 999.00 WHERE id = ?", ROW);

        reader.begin();
        assertThat(amountSeenBy(reader))
                .as("dirty read: uncommitted data")
                .isEqualByComparingTo("999.00");

        writer.rollback();

        // And now the value the reader acted on never existed. This is why nobody runs here.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");
        reader.commit();
    }

    @Test
    @DisplayName("READ COMMITTED stops dirty reads but not non-repeatable reads")
    void readCommittedAllowsNonRepeatableRead() {
        Session writer = session("writer");
        Session reader = session("reader").isolation("READ COMMITTED");

        reader.begin();
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        // Still uncommitted: invisible even to a READ COMMITTED reader.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");
        writer.commit();

        // Same row, same transaction, different answer - because RC takes a fresh ReadView for
        // every statement.
        assertThat(amountSeenBy(reader))
                .as("non-repeatable read")
                .isEqualByComparingTo("200.00");
        reader.commit();
    }

    @Test
    @DisplayName("REPEATABLE READ gives the same answer for the life of the transaction")
    void repeatableReadIsRepeatable() {
        Session writer = session("writer");
        Session reader = session("reader");   // REPEATABLE READ, the default

        reader.begin();
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        writer.commit();

        // The reader keeps the ReadView it took at its first read, so it keeps finding the
        // old version down the undo chain.
        assertThat(amountSeenBy(reader))
                .as("repeatable read: same snapshot")
                .isEqualByComparingTo("100.00");

        reader.commit();
        // Only once the transaction ends does the snapshot go with it.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("200.00");
    }

    private BigDecimal amountSeenBy(Session session) {
        return session.queryValue(
                "SELECT amount FROM " + table() + " WHERE id = ?", BigDecimal.class, ROW);
    }
}
