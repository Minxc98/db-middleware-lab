package com.pacvue.lab.mysql.behavior.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 04 §3.3 - RR and RC differ in exactly one thing: <em>when</em> the ReadView is taken.
 *
 * <p>The corollary catches people out: under RR the snapshot is taken at the first snapshot
 * <em>read</em>, not at {@code START TRANSACTION}. A transaction that opens, waits, and then
 * reads will see everything that was committed while it was waiting.
 */
class ReadViewTimingIT extends AbstractBehaviorIT {

    private static final long ROW = 1L;

    @Override
    protected String table() {
        return "lab_tx_readview";
    }

    @BeforeEach
    void seed() {
        insertOrders(ROW);
        jdbc.update("UPDATE " + table() + " SET amount = 100.00 WHERE id = ?", ROW);
    }

    @Test
    @DisplayName("under RR the snapshot is taken at the first read, not at START TRANSACTION")
    void snapshotIsTakenAtFirstRead() {
        Session writer = session("writer");
        Session reader = session("reader");

        reader.begin();                 // no read yet - no ReadView yet

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        writer.commit();

        // First read of the transaction: the ReadView is created now, so it includes the update
        // that landed after START TRANSACTION. "The transaction sees the database as of when it
        // started" is the common phrasing and it is wrong.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("200.00");

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 300.00 WHERE id = ?", ROW);
        writer.commit();

        // From here on it behaves as advertised: the view is fixed.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("200.00");
        reader.commit();
    }

    @Test
    @DisplayName("WITH CONSISTENT SNAPSHOT takes the ReadView immediately")
    void consistentSnapshotTakesItUpFront() {
        Session writer = session("writer");
        Session reader = session("reader");

        reader.beginWithSnapshot();     // ReadView created here, before any statement

        writer.begin();
        writer.execute("UPDATE " + table() + " SET amount = 200.00 WHERE id = ?", ROW);
        writer.commit();

        // This is what people usually mean when they say "the transaction's snapshot", and it
        // is also how mysqldump --single-transaction gets a consistent backup without locking
        // anything.
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");
        reader.commit();
    }

    @Test
    @DisplayName("under RC a new ReadView is taken for every statement")
    void readCommittedTakesOnePerStatement() {
        Session writer = session("writer");
        Session reader = session("reader").isolation("READ COMMITTED");

        reader.begin();
        assertThat(amountSeenBy(reader)).isEqualByComparingTo("100.00");

        for (String value : new String[]{"200.00", "300.00"}) {
            writer.begin();
            writer.execute("UPDATE " + table() + " SET amount = ? WHERE id = ?",
                    new BigDecimal(value), ROW);
            writer.commit();
            // Every read is a new view, so the reader tracks the writer step for step.
            assertThat(amountSeenBy(reader)).isEqualByComparingTo(value);
        }
        reader.commit();
    }

    @Test
    @DisplayName("a transaction always sees its own uncommitted writes")
    void aTransactionSeesItsOwnWrites() {
        Session session = session("self");

        session.begin();
        assertThat(amountSeenBy(session)).isEqualByComparingTo("100.00");
        session.execute("UPDATE " + table() + " SET amount = 500.00 WHERE id = ?", ROW);

        // The version on the row now carries this transaction's own trx_id, and the first rule
        // of the visibility algorithm is "trx_id == creator_trx_id -> visible".
        assertThat(amountSeenBy(session)).isEqualByComparingTo("500.00");

        Session other = session("other");
        assertThat(amountSeenBy(other))
                .as("...and nobody else does, until it commits")
                .isEqualByComparingTo("100.00");

        session.rollback();
    }

    private BigDecimal amountSeenBy(Session session) {
        return session.queryValue(
                "SELECT amount FROM " + table() + " WHERE id = ?", BigDecimal.class, ROW);
    }
}
