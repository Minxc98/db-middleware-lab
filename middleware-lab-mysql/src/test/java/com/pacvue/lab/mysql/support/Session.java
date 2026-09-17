package com.pacvue.lab.mysql.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/**
 * One MySQL session: its own physical connection, its own transaction, its own locks.
 *
 * <p>Everything about MVCC and locking is a statement about <em>two</em> sessions, so the
 * behaviour suite needs to hold several connections open at once and interleave them by hand.
 * That is what this is for - Spring's transaction management deliberately stays out of the way.
 *
 * <p>Each session also owns a single worker thread, so a statement that is <em>supposed</em> to
 * block (waiting on a row lock, an MDL, a gap) can be started with {@link #submit} and asserted
 * on with {@link #assertStillBlocked} instead of hanging the test.
 */
public final class Session implements AutoCloseable {

    private final String name;
    private final Connection connection;
    private final ExecutorService worker;
    private final long connectionId;

    /** Session variables this session changed. See {@link #sessionVariable}. */
    private final Set<String> changedVariables = new LinkedHashSet<>();

    private Session(String name, Connection connection, long connectionId) {
        this.name = name;
        this.connection = connection;
        this.connectionId = connectionId;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lab-session-" + name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * A session on a server that is not the one the Spring context points at - the replication
     * suite talks to two servers at once and neither of them is "the" data source.
     */
    public static Session connect(String url, String user, String password, String name) {
        try {
            return open(() -> java.sql.DriverManager.getConnection(url, user, password), name);
        } catch (RuntimeException e) {
            throw new IllegalStateException("could not connect session " + name + " to " + url, e);
        }
    }

    public static Session open(DataSource dataSource, String name) {
        return open(dataSource::getConnection, name);
    }

    private interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private static Session open(ConnectionSource connections, String name) {
        try {
            Connection connection = connections.get();
            connection.setAutoCommit(true);
            long id;
            try (PreparedStatement ps = connection.prepareStatement("SELECT CONNECTION_ID()");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                id = rs.getLong(1);
            }
            return new Session(name, connection, id);
        } catch (SQLException e) {
            throw new IllegalStateException("could not open session " + name, e);
        }
    }

    public String name() {
        return name;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * {@code CONNECTION_ID()} - the processlist id, which is how performance_schema finds us.
     *
     * <p>Read once when the session opens rather than on demand: the interesting moment to ask
     * "what is this session waiting for" is exactly the moment its connection is busy blocking
     * on a lock, and a second statement on that connection would queue behind the first.
     */
    public long connectionId() {
        return connectionId;
    }

    /**
     * {@code SET SESSION <name> = <value>}, remembered so {@link #close()} puts it back.
     *
     * <p>Always use this rather than a bare {@code SET SESSION}. A pooled connection is handed
     * on to the next borrower with whatever session state it was left in, and the driver only
     * knows to reset the settings that went through a JDBC method (autocommit, isolation). A
     * {@code lock_wait_timeout} set by one test will otherwise time out an unrelated statement
     * in another, minutes later, with nothing in the failure pointing back at the cause.
     */
    public Session sessionVariable(String variable, Object value) {
        if (changedVariables.add(variable)) {
            // Stash the old value in a user variable rather than reading it into Java. Round
            // tripping it through a string and back turns 31536000 into '31536000', and MySQL
            // is not uniformly happy to take a quoted number for an integer setting - which
            // fails the restore, quietly, and puts the leak straight back.
            execute("SET @lab_saved_" + variable + " = @@session." + variable);
        }
        execute("SET SESSION " + variable + " = ?", value);
        return this;
    }

    // --- transaction control -------------------------------------------------------------

    /**
     * {@code READ UNCOMMITTED} / {@code READ COMMITTED} / {@code REPEATABLE READ} /
     * {@code SERIALIZABLE}.
     *
     * <p>Set through JDBC rather than with {@code SET SESSION TRANSACTION ISOLATION LEVEL}: the
     * pool tracks what it handed out and resets it when the connection comes back. A level set
     * behind the pool's back leaks into whoever borrows the connection next.
     */
    public Session isolation(String level) {
        try {
            connection.setTransactionIsolation(isolationConstant(level));
            return this;
        } catch (SQLException e) {
            throw new SessionException(name, "SET ISOLATION " + level, e);
        }
    }

    public String isolation() {
        return queryValue("SELECT @@session.transaction_isolation", String.class);
    }

    /**
     * Opens a transaction.
     *
     * <p>This is {@code setAutoCommit(false)}, not a {@code START TRANSACTION} string, and the
     * distinction matters more than it looks. With autocommit left on at the JDBC level, the
     * driver and the pool both believe the connection is idle: nothing rolls back when the
     * session closes, and a connection carrying an open transaction and its locks goes back
     * into the pool for the next borrower. Transactions have to be visible to the layer that
     * manages the connection.
     *
     * <p>Like {@code START TRANSACTION}, the transaction actually begins at the first statement.
     */
    public Session begin() {
        try {
            connection.setAutoCommit(false);
            return this;
        } catch (SQLException e) {
            throw new SessionException(name, "BEGIN", e);
        }
    }

    /**
     * {@code START TRANSACTION WITH CONSISTENT SNAPSHOT} - takes the ReadView immediately
     * instead of at the first read. The difference is the whole point of
     * {@code ReadViewTimingIT}.
     */
    public Session beginWithSnapshot() {
        begin();
        execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
        return this;
    }

    public void commit() {
        endTransaction(true);
    }

    public void rollback() {
        endTransaction(false);
    }

    private void endTransaction(boolean commit) {
        try {
            if (connection.getAutoCommit()) {
                return;   // nothing open
            }
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new SessionException(name, commit ? "COMMIT" : "ROLLBACK", e);
        }
    }

    private static int isolationConstant(String level) {
        return switch (level.toUpperCase().replace('-', ' ')) {
            case "READ UNCOMMITTED" -> Connection.TRANSACTION_READ_UNCOMMITTED;
            case "READ COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            case "REPEATABLE READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "SERIALIZABLE" -> Connection.TRANSACTION_SERIALIZABLE;
            default -> throw new IllegalArgumentException("unknown isolation level: " + level);
        };
    }

    // --- statements ----------------------------------------------------------------------

    public void execute(String sql, Object... args) {
        update(sql, args);
    }

    public int update(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args)) {
            ps.execute();
            return ps.getUpdateCount();
        } catch (SQLException e) {
            throw new SessionException(name, sql, e);
        }
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        try (PreparedStatement ps = prepare(sql, args);
             ResultSet rs = ps.executeQuery()) {
            return readAll(rs);
        } catch (SQLException e) {
            throw new SessionException(name, sql, e);
        }
    }

    /** Single value of the first row, or {@code null} when the query returned nothing. */
    public <T> T queryValue(String sql, Class<T> type, Object... args) {
        try (PreparedStatement ps = prepare(sql, args);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? convert(rs.getObject(1), type) : null;
        } catch (SQLException e) {
            throw new SessionException(name, sql, e);
        }
    }

    public long count(String sql, Object... args) {
        Long value = queryValue(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * A session status counter such as {@code Handler_read_next}.
     *
     * <p>Read through a {@link Session} rather than the shared {@code JdbcTemplate}: session
     * counters belong to one connection, and the template hands out whichever connection the
     * pool has free, so consecutive calls through it may be counting different things.
     */
    public long status(String name) {
        Object value = query("SHOW SESSION STATUS LIKE ?", name).stream()
                .findFirst().map(row -> row.get("Value")).orElse(null);
        return value == null ? -1 : Long.parseLong(String.valueOf(value));
    }

    /** {@code FLUSH STATUS} - zeroes this session's counters so the next statement is measured alone. */
    public void flushStatus() {
        execute("FLUSH STATUS");
    }

    /** Warnings the last statement produced - {@code SHOW WARNINGS} as one string per warning. */
    public List<String> warnings() {
        return query("SHOW WARNINGS").stream()
                .map(row -> row.get("Level") + " " + row.get("Code") + ": " + row.get("Message"))
                .toList();
    }

    // --- asynchronous, for statements expected to block --------------------------------

    /**
     * Runs {@code sql} on this session's own thread and hands back the future. Use it for a
     * statement that should block: the test can then assert that it is still waiting, release
     * the blocker from another session, and assert it completes.
     */
    public Future<Integer> submit(String sql, Object... args) {
        return worker.submit(() -> update(sql, args));
    }

    /** Fails if {@code future} finished within {@code window} - i.e. it was not blocked after all. */
    public static void assertStillBlocked(Future<?> future, Duration window, String what) {
        try {
            Object value = future.get(window.toMillis(), TimeUnit.MILLISECONDS);
            throw new AssertionError(what + " was expected to block, but completed with: " + value);
        } catch (TimeoutException expected) {
            // still waiting - which is the point
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new AssertionError(what + " was expected to block, but failed", e.getCause());
        }
    }

    /** Waits for {@code future}, failing if it does not finish within {@code window}. */
    public static <T> T awaitCompletion(Future<T> future, Duration window, String what) {
        try {
            return future.get(window.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError(what + " never completed within " + window, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** The exception a blocked statement eventually failed with, or {@code null} if it succeeded. */
    public static Throwable failureOf(Future<?> future, Duration window) {
        try {
            future.get(window.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("statement neither succeeded nor failed within " + window, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        // shutdownNow first: if the worker is parked on a lock wait, closing the connection is
        // what actually wakes it, and we do not want to wait for it either way.
        worker.shutdownNow();
        restoreChangedVariables();
        try {
            if (!connection.isClosed() && !connection.getAutoCommit()) {
                // Leaving this to the pool would work, but only because autoCommit is false.
                // Doing it here keeps "the session let go of its locks" true by construction.
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // a session that failed mid-transaction is being torn down anyway
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // same
        }
    }

    private void restoreChangedVariables() {
        for (String variable : changedVariables) {
            try {
                execute("SET SESSION " + variable + " = @lab_saved_" + variable);
            } catch (RuntimeException ignored) {
                // the connection may already be unusable; the pool will discard it
            }
        }
        changedVariables.clear();
    }

    // --- plumbing ------------------------------------------------------------------------

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
        return ps;
    }

    static List<Map<String, Object>> readAll(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(Object value, Class<T> type) {
        if (value == null || type.isInstance(value)) {
            return (T) value;
        }
        if (type == String.class) {
            // MySQL hands back VARBINARY for several system columns; String.valueOf on the byte[]
            // would print an object id rather than the text.
            return (T) (value instanceof byte[] bytes ? new String(bytes) : String.valueOf(value));
        }
        if (value instanceof Number number) {
            if (type == Long.class) {
                return (T) Long.valueOf(number.longValue());
            }
            if (type == Integer.class) {
                return (T) Integer.valueOf(number.intValue());
            }
            if (type == Double.class) {
                return (T) Double.valueOf(number.doubleValue());
            }
            if (type == BigDecimal.class) {
                return (T) new BigDecimal(number.toString());
            }
        }
        if (type == Long.class || type == Integer.class) {
            String text = value instanceof byte[] bytes ? new String(bytes) : String.valueOf(value);
            return type == Long.class
                    ? (T) Long.valueOf(text.trim())
                    : (T) Integer.valueOf(text.trim());
        }
        throw new IllegalArgumentException("cannot convert " + value.getClass() + " to " + type);
    }

    /** Carries the session name and the statement, because a bare SQLException says neither. */
    public static class SessionException extends RuntimeException {

        private final transient String sessionName;

        SessionException(String sessionName, String sql, SQLException cause) {
            super("[" + sessionName + "] " + sql + " -> " + cause.getMessage(), cause);
            this.sessionName = sessionName;
        }

        public String sessionName() {
            return sessionName;
        }

        public SQLException sqlCause() {
            return (SQLException) getCause();
        }

        /** MySQL error number, e.g. 1213 deadlock, 1205 lock wait timeout, 1062 duplicate key. */
        public int errorCode() {
            return sqlCause().getErrorCode();
        }
    }
}
