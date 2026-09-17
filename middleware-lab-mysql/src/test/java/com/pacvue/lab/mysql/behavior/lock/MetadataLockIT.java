package com.pacvue.lab.mysql.behavior.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 05 §6 - metadata locks, and the cascade that makes a five-second {@code ALTER TABLE}
 * look like a total outage.
 *
 * <p>A transaction holds MDL SHARED_READ on every table it touched, until it commits. A DDL
 * wants MDL EXCLUSIVE. Requests queue in arrival order, so the DDL waits behind the transaction
 * - and every ordinary query that arrives afterwards waits behind the DDL, even though it only
 * wanted a shared lock that the transaction would happily have granted.
 */
class MetadataLockIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_lock_mdl";
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
    @DisplayName("a transaction holds a metadata lock on every table it read, until it commits")
    void readingATableTakesAMetadataLock() {
        Session reader = session("reader");
        reader.begin();
        reader.query("SELECT * FROM " + table() + " WHERE id = 10");

        // Not a row lock - a plain SELECT under MVCC takes none of those.
        assertThat(locks.rowLocksOf(reader, table())).isEmpty();
        // But it does take a metadata lock, and that one is held to the end of the transaction.
        assertThat(metadataLocksOn(table()))
                .anySatisfy(lock -> assertThat(lock.get("LOCK_TYPE")).isEqualTo("SHARED_READ"));

        reader.commit();
    }

    @Test
    @DisplayName("an open transaction blocks DDL, and the queued DDL then blocks new readers")
    void longTransactionBlocksDdlWhichBlocksEveryoneElse() {
        Session longTransaction = session("long-transaction");
        Session ddl = session("ddl");
        Session latecomer = session("latecomer");

        // 1. A transaction that read the table and has not committed. In production this is
        //    usually an idle connection somebody forgot, not a slow query.
        longTransaction.begin();
        longTransaction.query("SELECT * FROM " + table() + " WHERE id = 10");

        // 2. ALTER TABLE wants MDL EXCLUSIVE and queues behind it.
        Future<Integer> alter = ddl.submit("ALTER TABLE " + table() + " ADD COLUMN note VARCHAR(10)");
        Session.assertStillBlocked(alter, Duration.ofSeconds(1), "ALTER behind an open transaction");

        assertThat(metadataLocksOn(table()))
                .as("the pending exclusive request is visible in performance_schema")
                .anySatisfy(lock -> {
                    assertThat(lock.get("LOCK_TYPE")).isEqualTo("EXCLUSIVE");
                    assertThat(lock.get("LOCK_STATUS")).isEqualTo("PENDING");
                });

        // 3. And now a perfectly ordinary SELECT - which wants only a shared lock, compatible
        //    with the one the transaction holds - is stuck behind the queued exclusive request.
        //    This is the step that turns one stale connection into "the whole table is down".
        Future<Integer> innocentRead = latecomer.submit("SELECT COUNT(*) FROM " + table());
        Session.assertStillBlocked(innocentRead, Duration.ofSeconds(1),
                "a plain SELECT arriving after the queued ALTER");

        // 4. Release the original transaction and the queue drains in order.
        longTransaction.commit();
        Session.awaitCompletion(alter, Duration.ofSeconds(15), "the ALTER");
        Session.awaitCompletion(innocentRead, Duration.ofSeconds(15), "the queued SELECT");

        assertThat(jdbc.queryForList("SHOW COLUMNS FROM " + table()))
                .extracting(row -> row.get("Field"))
                .contains("note");
    }

    @Test
    @DisplayName("lock_wait_timeout is what bounds an MDL wait - and its default is a year")
    void metadataWaitsAreBoundedByLockWaitTimeout() {
        Session longTransaction = session("long-transaction");
        Session ddl = session("ddl");

        // innodb_lock_wait_timeout does not apply here: that one is for row locks. MDL waits are
        // governed by lock_wait_timeout, whose default is 31536000 seconds - 365 days. A DDL
        // blocked on a forgotten transaction will therefore wait effectively forever unless the
        // session lowers it, which is why every online-DDL runbook starts by setting it.
        assertThat(variable("lock_wait_timeout")).isEqualTo("31536000");

        longTransaction.begin();
        longTransaction.query("SELECT * FROM " + table() + " WHERE id = 10");

        // Through sessionVariable(), not a bare SET SESSION: this connection goes back to the
        // pool afterwards, and a one-second lock_wait_timeout left on it would time out an
        // unrelated statement in a later test.
        ddl.sessionVariable("lock_wait_timeout", 1);
        Throwable failure = Session.failureOf(
                ddl.submit("ALTER TABLE " + table() + " ADD COLUMN note VARCHAR(10)"),
                Duration.ofSeconds(10));

        assertThat(failure)
                .as("the ALTER should give up rather than queue indefinitely")
                .isInstanceOf(Session.SessionException.class);
        // 1205, the same error number a row-lock timeout uses.
        assertThat(((Session.SessionException) failure).errorCode()).isEqualTo(1205);

        longTransaction.commit();
    }

    private List<Map<String, Object>> metadataLocksOn(String tableName) {
        return jdbc.queryForList("""
                SELECT OBJECT_TYPE, LOCK_TYPE, LOCK_STATUS
                FROM performance_schema.metadata_locks
                WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = ?
                """, tableName);
    }
}
