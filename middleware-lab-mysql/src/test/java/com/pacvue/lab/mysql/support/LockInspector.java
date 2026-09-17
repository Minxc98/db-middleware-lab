package com.pacvue.lab.mysql.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads {@code performance_schema.data_locks} - the only way to see what InnoDB actually locked
 * rather than what you believe it locked.
 *
 * <p>The column that matters is {@code LOCK_MODE}, and its vocabulary maps straight onto the
 * three shapes in the notes (05 §3):
 *
 * <pre>
 *   X,REC_NOT_GAP   record lock   - the row only
 *   X,GAP           gap lock      - the open interval before the row, no row
 *   X               next-key lock - gap + row, i.e. (previous, this]
 *   X,INSERT_INTENTION  a waiting INSERT that a gap lock is holding up
 *   IX / IS         table-level intention lock (05 §2)
 * </pre>
 *
 * <p>{@code LOCK_DATA} names the index entry the lock sits on - a primary-key value, a secondary
 * key value plus its primary key, or {@code supremum pseudo-record} for the gap that runs to the
 * end of the index.
 */
public final class LockInspector {

    private final JdbcTemplate jdbc;

    public LockInspector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param index  index the lock sits on ({@code PRIMARY}, a secondary index name, or
     *               {@code null} for a table-level lock)
     * @param type   {@code RECORD} or {@code TABLE}
     * @param mode   see the class javadoc
     * @param status {@code GRANTED} or {@code WAITING}
     * @param data   the locked index entry, or {@code supremum pseudo-record}
     */
    public record Lock(String index, String type, String mode, String status, String data) {

        public boolean isRecordOnly() {
            return "X,REC_NOT_GAP".equals(mode) || "S,REC_NOT_GAP".equals(mode);
        }

        public boolean isGapOnly() {
            return "X,GAP".equals(mode) || "S,GAP".equals(mode);
        }

        /** A bare {@code X}/{@code S} on a record is a next-key lock: the gap plus the row. */
        public boolean isNextKey() {
            return "X".equals(mode) || "S".equals(mode);
        }

        @Override
        public String toString() {
            return "%s %s %s %s %s".formatted(type, index, mode, status, data);
        }
    }

    /** Every lock held or awaited by one session on one table, row locks and table locks alike. */
    public List<Lock> locksOf(long connectionId, String table) {
        return jdbc.query("""
                        SELECT INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA
                        FROM performance_schema.data_locks
                        WHERE OBJECT_NAME = ?
                          AND THREAD_ID = (SELECT THREAD_ID FROM performance_schema.threads
                                           WHERE PROCESSLIST_ID = ?)
                        ORDER BY LOCK_TYPE DESC, INDEX_NAME, LOCK_DATA
                        """,
                (rs, rowNum) -> new Lock(
                        rs.getString("INDEX_NAME"),
                        rs.getString("LOCK_TYPE"),
                        rs.getString("LOCK_MODE"),
                        rs.getString("LOCK_STATUS"),
                        rs.getString("LOCK_DATA")),
                table, connectionId);
    }

    /** Row locks only - the table-level intention lock is always there and rarely interesting. */
    public List<Lock> rowLocksOf(long connectionId, String table) {
        return locksOf(connectionId, table).stream()
                .filter(lock -> "RECORD".equals(lock.type()))
                .toList();
    }

    public List<Lock> rowLocksOf(Session session, String table) {
        return rowLocksOf(session.connectionId(), table);
    }

    public List<Lock> locksOf(Session session, String table) {
        return locksOf(session.connectionId(), table);
    }

    /** Distinct {@code LOCK_MODE} values on rows, which is usually the whole assertion. */
    public List<String> rowLockModes(Session session, String table) {
        return rowLocksOf(session, table).stream().map(Lock::mode).distinct().sorted().toList();
    }

    /** Locked index entries, e.g. {@code [5, 10, supremum pseudo-record]}. */
    public List<String> lockedRows(Session session, String table) {
        return rowLocksOf(session, table).stream().map(Lock::data).toList();
    }

    /** True once the session is parked waiting for a lock somebody else holds. */
    public boolean isWaiting(Session session) {
        Integer waits = jdbc.queryForObject("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads t ON t.THREAD_ID = w.REQUESTING_THREAD_ID
                WHERE t.PROCESSLIST_ID = ?
                """, Integer.class, session.connectionId());
        return waits != null && waits > 0;
    }

    /** {@code SHOW ENGINE INNODB STATUS} - the deadlock and history-list text lives in here. */
    public String innodbStatus() {
        return jdbc.query("SHOW ENGINE INNODB STATUS",
                rs -> rs.next() ? rs.getString("Status") : "");
    }
}
