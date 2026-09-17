package com.pacvue.lab.mysql.behavior.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 06 §2/§5 - redo vs binlog, and the two-phase commit that keeps them agreeing.
 *
 * <p>Nothing here can watch the prepare/commit handshake itself - it happens inside a commit and
 * leaves no interface to observe. What is observable is its purpose: a committed transaction
 * moves <em>both</em> logs, a rolled-back one moves only the redo log, and the redo log always
 * runs ahead of the data pages.
 */
class RedoAndBinlogIT extends AbstractBehaviorIT {

    private static final Pattern LSN =
            Pattern.compile("Log sequence number\\s+(\\d+)");
    private static final Pattern CHECKPOINT =
            Pattern.compile("Last checkpoint at\\s+(\\d+)");

    @Override
    protected String table() {
        return "lab_log_redo";
    }

    @Override
    protected void createTable() {
        jdbc.execute("CREATE TABLE " + table()
                + " (id INT PRIMARY KEY, v VARCHAR(64)) ENGINE=InnoDB");
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO " + table() + " VALUES (1, 'a'), (2, 'b'), (3, 'c')");
    }

    @Test
    @DisplayName("the double-1 setting: nothing committed is lost, at the cost of two fsyncs")
    void durabilityIsSetToTheSafeCombination() {
        assertThat(variable("innodb_flush_log_at_trx_commit")).isEqualTo("1");
        assertThat(variable("sync_binlog")).isEqualTo("1");

        // Two fsyncs per commit would be ruinous one transaction at a time. Group commit is what
        // makes it affordable: concurrent commits are batched and share a flush.
        assertThat(variable("binlog_group_commit_sync_delay")).isNotNull();
    }

    @Test
    @DisplayName("a commit moves both logs; a rollback moves only the redo log")
    void commitMovesBothLogsAndRollbackMovesOnlyRedo() {
        long lsnBefore = logSequenceNumber();
        long binlogBefore = binlogPosition();

        Session session = session("writer");
        session.begin();
        session.execute("UPDATE " + table() + " SET v = 'committed' WHERE id = 1");
        session.commit();

        assertThat(logSequenceNumber()).isGreaterThan(lsnBefore);
        assertThat(binlogPosition())
                .as("a committed change has to reach the binlog, or a replica would never see it")
                .isGreaterThan(binlogBefore);

        long lsnAfterCommit = logSequenceNumber();
        long binlogAfterCommit = binlogPosition();

        session.begin();
        session.execute("UPDATE " + table() + " SET v = 'rolled back' WHERE id = 2");
        session.rollback();

        // The redo log moved: the page was modified in the buffer pool, and undoing it is itself
        // work that has to be crash-safe.
        assertThat(logSequenceNumber()).isGreaterThan(lsnAfterCommit);
        // The binlog did not. It carries committed transactions only - which is exactly what
        // makes it usable as the arbiter during crash recovery: if the binlog has the
        // transaction, it happened.
        assertThat(binlogPosition())
                .as("a rolled-back transaction leaves no trace in the binlog")
                .isEqualTo(binlogAfterCommit);
    }

    @Test
    @DisplayName("write-ahead logging: the redo log runs ahead of the checkpoint")
    void redoRunsAheadOfTheDataPages() {
        for (int i = 0; i < 200; i++) {
            jdbc.update("UPDATE " + table() + " SET v = ? WHERE id = 1", "v" + i);
        }

        String status = locks.innodbStatus();
        long lsn = extract(LSN, status);
        long checkpoint = extract(CHECKPOINT, status);

        // The gap between them is the redo that has been written but whose dirty pages are not
        // on disk yet. That gap is the whole point of WAL: commits pay for a sequential write to
        // the log, and the random writes to the data pages happen later, in the background.
        // It is also what crash recovery replays.
        assertThat(lsn)
                .as("LSN %s vs last checkpoint %s", lsn, checkpoint)
                .isGreaterThan(checkpoint);
    }

    @Test
    @DisplayName("redo is a fixed-size circle; the binlog is an ever-growing archive")
    void redoIsCircularAndBinlogIsAppendOnly() {
        // The redo log has a capacity, not a length. When the write position catches up with the
        // checkpoint, InnoDB has to stop and flush dirty pages - the stall the notes describe.
        String capacity = variable("innodb_redo_log_capacity");
        assertThat(capacity).isNotNull();
        assertThat(Long.parseLong(capacity)).isPositive();

        // The binlog has no capacity, only an expiry. Nothing overwrites it; old files are
        // deleted on a timer once they are older than this.
        assertThat(Long.parseLong(variable("binlog_expire_logs_seconds"))).isPositive();

        // And this is why only one of them can restore a backup to a point in time.
        assertThat(jdbc.queryForList("SHOW BINARY LOGS")).isNotEmpty();
    }

    private long logSequenceNumber() {
        return extract(LSN, locks.innodbStatus());
    }

    private long binlogPosition() {
        Map<String, Object> status = jdbc.queryForMap("SHOW MASTER STATUS");
        return ((Number) status.get("Position")).longValue();
    }

    private static long extract(Pattern pattern, String status) {
        Matcher matcher = pattern.matcher(status);
        if (!matcher.find()) {
            throw new AssertionError("pattern " + pattern + " not found in SHOW ENGINE INNODB STATUS");
        }
        return Long.parseLong(matcher.group(1));
    }
}
