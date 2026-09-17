package com.pacvue.lab.mysql.behavior.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import com.pacvue.lab.mysql.support.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 06 §4 - the binlog: Server-layer, logical, append-only, and the basis of both replication
 * and point-in-time recovery.
 *
 * <p>The format choice is the part with teeth. STATEMENT replicates the statement and trusts it to
 * mean the same thing on the replica; ROW replicates what the statement did.
 */
class BinlogFormatIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_log_binlog";
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
    @DisplayName("the binlog is on, row-formatted and fsynced on every commit")
    void binlogConfiguration() {
        assertThat(variable("log_bin")).isEqualTo("ON");
        assertThat(variable("binlog_format")).isEqualTo("ROW");
        // FULL: both the before and after image of every changed row. MINIMAL would log only the
        // key plus changed columns - smaller, but then the binlog no longer carries enough to
        // reconstruct the old row, which some CDC consumers need.
        assertThat(variable("binlog_row_image")).isEqualTo("FULL");
        assertThat(variable("sync_binlog")).isEqualTo("1");
    }

    @Test
    @DisplayName("STATEMENT format warns when a statement would not replay the same way")
    void statementFormatWarnsAboutNonDeterminism() {
        Session session = session("writer");
        session.sessionVariable("binlog_format", "STATEMENT");

        session.execute("INSERT INTO " + table() + " VALUES (4, UUID())");

        List<String> warnings = session.warnings();

        // Note 1592. The row is written, the statement goes into the binlog verbatim, and a
        // replica replaying it will call UUID() itself and store a different value. The primary
        // and the replica now disagree, silently, forever.
        assertThat(warnings).anySatisfy(warning -> {
            assertThat(warning).contains("1592");
            assertThat(warning).contains("Unsafe statement written to the binary log");
            assertThat(warning).contains("may return a different value on the replica");
        });
    }

    @Test
    @DisplayName("ROW logs the rows it changed; STATEMENT logs the statement")
    void rowFormatLogsRowsAndStatementFormatLogsSql() {
        String rowEvents = eventsWrittenBy(() ->
                jdbc.update("UPDATE " + table() + " SET v = 'row-format' WHERE id > 0"));

        // One UPDATE, three rows: ROW format writes an Update_rows event carrying all three,
        // whatever the statement looked like.
        assertThat(rowEvents).contains("Update_rows");
        assertThat(rowEvents).doesNotContain("row-format");

        Session session = session("writer");
        session.sessionVariable("binlog_format", "STATEMENT");
        String statementEvents = eventsWrittenBy(() ->
                session.execute("UPDATE " + table() + " SET v = 'stmt-format' WHERE id > 0"));

        // STATEMENT writes the SQL text. Smaller - one line instead of three row images - and
        // that is the entire argument in its favour.
        assertThat(statementEvents).contains("stmt-format");
        assertThat(statementEvents).doesNotContain("Update_rows");
    }

    @Test
    @DisplayName("the binlog is append-only and rotates into new files rather than overwriting")
    void binlogAppendsAndRotates() {
        long positionBefore = binlogPosition();
        jdbc.update("UPDATE " + table() + " SET v = 'x' WHERE id = 1");
        long positionAfter = binlogPosition();

        assertThat(positionAfter)
                .as("the position only ever moves forward within a file")
                .isGreaterThan(positionBefore);

        int filesBefore = jdbc.queryForList("SHOW BINARY LOGS").size();
        jdbc.execute("FLUSH BINARY LOGS");
        List<Map<String, Object>> filesAfter = jdbc.queryForList("SHOW BINARY LOGS");

        // Rotation starts a new file and keeps the old one. This is what makes point-in-time
        // recovery possible, and it is the opposite of the redo log, which is a fixed set of
        // files written in a circle (see RedoAndBinlogIT).
        assertThat(filesAfter).hasSize(filesBefore + 1);
        assertThat(binlogPosition())
                .as("a fresh file starts near zero - positions are per file, not global")
                .isLessThan(positionAfter);
    }

    /** Binlog events written while {@code work} ran, as one string. */
    private String eventsWrittenBy(Runnable work) {
        Map<String, Object> before = jdbc.queryForMap("SHOW MASTER STATUS");
        String file = String.valueOf(before.get("File"));
        long from = ((Number) before.get("Position")).longValue();

        work.run();

        // SHOW BINLOG EVENTS with no IN clause reads the *first* binlog file, not the current
        // one - a reliable way to look at the wrong data for a while.
        return jdbc.queryForList(
                        "SHOW BINLOG EVENTS IN '" + file + "' FROM " + from).stream()
                .map(String::valueOf)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private long binlogPosition() {
        return ((Number) jdbc.queryForMap("SHOW MASTER STATUS").get("Position")).longValue();
    }
}
