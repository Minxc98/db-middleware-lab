package com.pacvue.lab.mysql.repository;

import com.pacvue.lab.mysql.config.MysqlLabProperties;
import com.pacvue.lab.mysql.domain.OrderRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain JDBC access to the lab's order table.
 *
 * <p>DDL lives here as a string rather than in a migration tool: the exact index set is the
 * thing under test, and tests build variants of it.
 */
@Repository
public class OrderJdbcRepository {

    /**
     * Schema exercised by the notes:
     * <ul>
     *   <li>{@code id} auto-increment {@code BIGINT} - the clustered index (notes 03 §7)</li>
     *   <li>{@code uk_order_no} unique secondary index - equality on it degenerates to a
     *       record lock when it hits (notes 05 §4)</li>
     *   <li>{@code idx_user_status_created} composite - leftmost prefix, covering index, ICP</li>
     * </ul>
     */
    public static final String DDL_TEMPLATE = """
            CREATE TABLE %s (
                id          BIGINT       NOT NULL AUTO_INCREMENT,
                order_no    VARCHAR(32)  NOT NULL,
                user_id     BIGINT       NOT NULL,
                status      TINYINT      NOT NULL,
                amount      DECIMAL(12,2) NOT NULL,
                remark      VARCHAR(255) NULL,
                created_at  DATETIME(3)  NOT NULL,
                PRIMARY KEY (id),
                UNIQUE KEY uk_order_no (order_no),
                KEY idx_user_status_created (user_id, status, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private static final RowMapper<OrderRow> MAPPER = (rs, rowNum) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("user_id"),
            rs.getInt("status"),
            rs.getBigDecimal("amount"),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;
    private final String table;

    public OrderJdbcRepository(JdbcTemplate jdbc, MysqlLabProperties properties) {
        this.jdbc = jdbc;
        this.table = properties.table();
    }

    public String table() {
        return table;
    }

    public void recreateTable() {
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        jdbc.execute(DDL_TEMPLATE.formatted(table));
    }

    /** One JDBC batch. Returns the number of rows the driver reports as written. */
    public int insertBatch(List<OrderRow> rows) {
        int[][] counts = jdbc.batchUpdate(
                "INSERT INTO " + table + " (order_no, user_id, status, amount, remark, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.orderNo());
                    ps.setLong(2, row.userId());
                    ps.setInt(3, row.status());
                    ps.setBigDecimal(4, row.amount());
                    ps.setString(5, row.remark());
                    ps.setTimestamp(6, Timestamp.valueOf(row.createdAt()));
                });
        int written = 0;
        for (int[] batch : counts) {
            for (int count : batch) {
                written += Math.max(count, 0);
            }
        }
        return written;
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    public List<OrderRow> findByUser(long userId, int limit) {
        return jdbc.query(
                "SELECT * FROM " + table + " WHERE user_id = ? ORDER BY id LIMIT ?",
                MAPPER, userId, limit);
    }

    /** The offset form of paging - the one that gets slower the deeper you go (notes 08 §4.1). */
    public List<OrderRow> pageByOffset(int offset, int size) {
        return jdbc.query(
                "SELECT * FROM " + table + " ORDER BY id LIMIT ?, ?", MAPPER, offset, size);
    }

    /** The bookmark form - cost independent of how deep you are (notes 08 §4.1). */
    public List<OrderRow> pageAfter(long lastId, int size) {
        return jdbc.query(
                "SELECT * FROM " + table + " WHERE id > ? ORDER BY id LIMIT ?", MAPPER, lastId, size);
    }

    public BigDecimal sumAmount() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM " + table, BigDecimal.class);
    }
}
