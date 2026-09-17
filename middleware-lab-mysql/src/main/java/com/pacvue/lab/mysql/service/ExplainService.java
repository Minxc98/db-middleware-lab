package com.pacvue.lab.mysql.service;

import com.pacvue.lab.mysql.domain.ExplainRow;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code EXPLAIN} in its three useful forms.
 *
 * <p>Tabular for "which index, how many rows"; JSON for the cost numbers and the nesting;
 * {@code EXPLAIN ANALYZE} (8.0.18+) for what actually happened rather than what the optimizer
 * guessed - it runs the statement.
 */
@Service
public class ExplainService {

    private final JdbcTemplate jdbc;

    public ExplainService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ExplainRow> explain(String sql, Object... args) {
        return jdbc.query("EXPLAIN " + sql, (rs, rowNum) -> new ExplainRow(
                rs.getString("table"),
                rs.getString("partitions"),
                rs.getString("type"),
                rs.getString("possible_keys"),
                rs.getString("key"),
                intOrNull(rs.getObject("key_len")),
                longOrNull(rs.getObject("rows")),
                doubleOrNull(rs.getObject("filtered")),
                rs.getString("Extra")), args);
    }

    /** First (outermost) EXPLAIN line - what you want for a single-table query. */
    public ExplainRow explainFirst(String sql, Object... args) {
        List<ExplainRow> rows = explain(sql, args);
        if (rows.isEmpty()) {
            throw new IllegalStateException("EXPLAIN returned no rows for: " + sql);
        }
        return rows.get(0);
    }

    public String explainJson(String sql, Object... args) {
        return jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class, args);
    }

    /**
     * {@code EXPLAIN ANALYZE} - note this <em>executes</em> the statement, so never point it at
     * something with side effects. Returns the tree as text, one line per row of output.
     */
    public String explainAnalyze(String sql, Object... args) {
        return jdbc.queryForList("EXPLAIN ANALYZE " + sql, args).stream()
                .flatMap(row -> row.values().stream())
                .map(String::valueOf)
                .collect(Collectors.joining("\n"));
    }

    // EXPLAIN is not a real table and its columns do not come back with consistent JDBC types -
    // key_len arrives as a string, rows as a long. Convert rather than cast.

    private static Integer intOrNull(Object value) {
        Number number = number(value);
        return number == null ? null : number.intValue();
    }

    private static Long longOrNull(Object value) {
        Number number = number(value);
        return number == null ? null : number.longValue();
    }

    private static Double doubleOrNull(Object value) {
        Number number = number(value);
        return number == null ? null : number.doubleValue();
    }

    private static Number number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n;
        }
        String text = value instanceof byte[] bytes ? new String(bytes) : String.valueOf(value);
        return text.isBlank() ? null : new java.math.BigDecimal(text.trim());
    }

    /** Raw rows, for when a test wants a column this module's record does not carry. */
    public List<Map<String, Object>> explainRaw(String sql, Object... args) {
        return jdbc.queryForList("EXPLAIN " + sql, args);
    }
}
