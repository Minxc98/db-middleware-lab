package com.pacvue.lab.mysql.support;

import com.pacvue.lab.mysql.repository.OrderJdbcRepository;
import com.pacvue.lab.mysql.service.ExplainService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the behaviour suite: one table per test class, recreated before each test.
 *
 * <p>Each class owning its own table matters more here than it does for Elasticsearch indices -
 * these tests take table-level metadata locks and read {@code performance_schema.data_locks}
 * filtered by table name, so a shared table would make them see each other.
 *
 * <p>{@link JdbcTemplate} is the observer connection: schema setup, {@code EXPLAIN}, status
 * counters, lock inspection. Anything that needs a transaction of its own goes through
 * {@link #session(String)}.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractBehaviorIT {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected ExplainService explain;

    protected LockInspector locks;

    private final List<Session> openSessions = new ArrayList<>();

    /** Table this test class owns. Keep it unique per class. */
    protected abstract String table();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MysqlContainerFactory::jdbcUrl);
        registry.add("spring.datasource.username", MysqlContainerFactory::username);
        registry.add("spring.datasource.password", MysqlContainerFactory::password);
    }

    @BeforeEach
    void recreateTable() {
        locks = new LockInspector(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS " + table());
        createTable();
    }

    @AfterEach
    void closeSessions() {
        // Reverse order: a session that is blocked is usually waiting on one opened before it,
        // and closing the holder first lets the waiter fail fast instead of on its timeout.
        for (int i = openSessions.size() - 1; i >= 0; i--) {
            openSessions.get(i).close();
        }
        openSessions.clear();
    }

    /**
     * Creates the table this test class needs. The default is the lab's order table; override
     * for a different index set, row format or storage engine.
     */
    protected void createTable() {
        jdbc.execute(OrderJdbcRepository.DDL_TEMPLATE.formatted(table()));
    }

    /** A connection of its own, closed automatically when the test ends. */
    protected Session session(String name) {
        Session session = Session.open(dataSource, name);
        openSessions.add(session);
        return session;
    }

    // --- fixtures -------------------------------------------------------------------------

    /**
     * Inserts rows with the primary keys you name, so gaps are where you put them.
     *
     * <p>Gap and next-key lock tests are entirely about which intervals exist, so
     * {@code insertOrders(5, 10, 15)} and its {@code (5,10)} and {@code (10,15)} gaps are the
     * usual starting point.
     */
    protected void insertOrders(long... ids) {
        for (long id : ids) {
            jdbc.update("INSERT INTO " + table()
                            + " (id, order_no, user_id, status, amount, remark, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id,
                    "NO-%09d".formatted(id),
                    id % 10,
                    (int) (id % 5),
                    BigDecimal.valueOf(id).setScale(2),
                    "row " + id,
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(id));
        }
    }

    /**
     * A deliberately small table for the lock suite: one primary key, one unique secondary
     * index, one non-unique secondary index, one unindexed column.
     *
     * <p>The order table would work too, but its composite index makes every
     * {@code LOCK_DATA} value a four-part tuple, and the assertions are about lock
     * <em>shapes</em>, not about reading tuples.
     */
    protected static final String LOCK_TABLE_DDL = """
            CREATE TABLE %s (
                id       BIGINT      NOT NULL,
                order_no VARCHAR(32) NOT NULL,
                user_id  BIGINT      NOT NULL,
                remark   VARCHAR(50) NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_order_no (order_no),
                KEY idx_user (user_id)
            ) ENGINE=InnoDB
            """;

    protected void createLockTable() {
        jdbc.execute(LOCK_TABLE_DDL.formatted(table()));
    }

    /**
     * Rows at the primary keys you name, with {@code user_id = id * 10} so secondary-index
     * order matches primary-key order and the gaps are easy to reason about.
     */
    protected void insertLockRows(long... ids) {
        for (long id : ids) {
            jdbc.update("INSERT INTO " + table() + " (id, order_no, user_id, remark)"
                            + " VALUES (?, ?, ?, ?)",
                    id, "NO-" + id, id * 10, "row-" + id);
        }
    }

    /** {@code count} rows with auto-increment keys 1..count and realistic column spread. */
    protected void insertSequentialOrders(int count) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            batch.add(new Object[]{
                    "NO-%09d".formatted(i),
                    (long) (i % 50),
                    i % 5,
                    BigDecimal.valueOf(i % 997).setScale(2),
                    "row " + i,
                    LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i)});
        }
        jdbc.batchUpdate("INSERT INTO " + table()
                + " (order_no, user_id, status, amount, remark, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", batch);
    }

    // --- server introspection --------------------------------------------------------------

    /** A server variable, e.g. {@code variable("innodb_page_size")}. */
    protected String variable(String name) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SHOW VARIABLES LIKE ?", name);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("Value"));
    }

    /**
     * A session status counter, e.g. {@code Handler_read_next}.
     *
     * <p>These are the honest measure of how much work a statement did - unlike wall-clock time
     * they do not depend on cache state, so a test can assert on them.
     */
    protected long sessionStatus(String name) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SHOW SESSION STATUS LIKE ?", name);
        return rows.isEmpty() ? -1 : Long.parseLong(String.valueOf(rows.get(0).get("Value")));
    }

    protected long globalStatus(String name) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SHOW GLOBAL STATUS LIKE ?", name);
        return rows.isEmpty() ? -1 : Long.parseLong(String.valueOf(rows.get(0).get("Value")));
    }

    /**
     * InnoDB names a tablespace {@code schema/table}. {@code INNODB_TABLES},
     * {@code INNODB_TABLESPACES} and {@code INNODB_INDEXES} all key on that name rather than on
     * the schema and table separately.
     */
    protected String tablespaceOf(String tableName) {
        return jdbc.queryForObject("SELECT DATABASE()", String.class) + "/" + tableName;
    }

    protected String tablespaceName() {
        return tablespaceOf(table());
    }

    /** Refreshes the optimizer statistics; without it row estimates on a fresh table wander. */
    protected void analyze() {
        jdbc.execute("ANALYZE TABLE " + table());
    }
}
