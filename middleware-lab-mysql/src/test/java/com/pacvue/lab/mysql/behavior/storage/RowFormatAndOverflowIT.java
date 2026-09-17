package com.pacvue.lab.mysql.behavior.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 02 - tablespace / segment / extent / page / row, row formats and row overflow.
 *
 * <p>The page is the unit InnoDB reads and writes, and almost every "why is it like that"
 * answer in the notes bottoms out in "because a row has to fit in half a 16KB page".
 */
class RowFormatAndOverflowIT extends AbstractBehaviorIT {

    private static final String NO_PK_TABLE = "lab_storage_no_pk";
    private static final String WIDE_TABLE = "lab_storage_wide";

    @Override
    protected String table() {
        return "lab_storage_order";
    }

    @Override
    protected void createTable() {
        super.createTable();
        jdbc.execute("DROP TABLE IF EXISTS " + NO_PK_TABLE);
        jdbc.execute("DROP TABLE IF EXISTS " + WIDE_TABLE);
    }

    @Test
    @DisplayName("pages are 16KB and the default row format is DYNAMIC")
    void pageSizeAndDefaultRowFormat() {
        assertThat(variable("innodb_page_size")).isEqualTo("16384");
        assertThat(variable("innodb_default_row_format")).isEqualTo("dynamic");

        String rowFormat = jdbc.queryForObject(
                "SELECT ROW_FORMAT FROM information_schema.INNODB_TABLES WHERE NAME = ?",
                String.class, tablespaceName());

        assertThat(rowFormat).isEqualTo("Dynamic");
    }

    @Test
    @DisplayName("innodb_file_per_table gives each table its own .ibd, sized in whole pages")
    void eachTableIsItsOwnTablespaceFile() {
        assertThat(variable("innodb_file_per_table")).isEqualTo("ON");

        Long fileSize = jdbc.queryForObject(
                "SELECT FILE_SIZE FROM information_schema.INNODB_TABLESPACES WHERE NAME = ?",
                Long.class, tablespaceName());
        String path = jdbc.queryForObject("""
                SELECT d.PATH FROM information_schema.INNODB_DATAFILES d
                JOIN information_schema.INNODB_TABLESPACES s ON s.SPACE = d.SPACE
                WHERE s.NAME = ?
                """, String.class, tablespaceName());

        assertThat(path).endsWith(table() + ".ibd");
        // An empty table is not zero bytes: it already holds its header, segment inode and
        // root pages. Whatever the size is, it is a whole number of 16KB pages.
        assertThat(fileSize).isNotNull().isPositive();
        assertThat(fileSize % 16384).as("file size %s is a whole number of pages", fileSize).isZero();
    }

    @Test
    @DisplayName("COMPACT keeps a 768-byte prefix in the page, so a wide row will not fit")
    void compactKeepsPrefixesInPageAndDynamicDoesNot() {
        // 16 columns that will each hold 1000 bytes - past the 767-byte threshold, so every one
        // of them is stored off-page.
        //   COMPACT: 768-byte prefix + 20-byte pointer stays in the page -> 16 * 788 = 12608 bytes,
        //            past the ~8126 a row may occupy in a 16KB page. Rejected at DDL time.
        //   DYNAMIC: 20-byte pointer only -> 320 bytes. Fits easily.
        String columns = IntStream.rangeClosed(1, 16)
                .mapToObj("c%d VARCHAR(1000)"::formatted)
                .collect(Collectors.joining(", "));

        assertThatThrownBy(() -> jdbc.execute(
                "CREATE TABLE " + WIDE_TABLE + " (id INT PRIMARY KEY, " + columns + ")"
                        + " ROW_FORMAT=COMPACT"))
                // MySQL rejects this at DDL time, before a single row exists - it can already
                // see that no value assignment would fit. The message names the 768.
                .hasStackTraceContaining("Row size too large")
                .hasStackTraceContaining("768");

        jdbc.execute("CREATE TABLE " + WIDE_TABLE + " (id INT PRIMARY KEY, " + columns + ")"
                + " ROW_FORMAT=DYNAMIC");

        assertThat(jdbc.queryForObject(
                "SELECT ROW_FORMAT FROM information_schema.INNODB_TABLES WHERE NAME = ?",
                String.class, tablespaceOf(WIDE_TABLE)))
                .isEqualTo("Dynamic");
    }

    @Test
    @DisplayName("a table without a primary key still gets a clustered index - a hidden one")
    void tableWithoutPrimaryKeyGetsHiddenClusteredIndex() {
        jdbc.execute("CREATE TABLE " + NO_PK_TABLE + " (a INT, b VARCHAR(20))");

        String indexName = jdbc.queryForObject("""
                SELECT i.NAME FROM information_schema.INNODB_INDEXES i
                JOIN information_schema.INNODB_TABLES t ON t.TABLE_ID = i.TABLE_ID
                WHERE t.NAME = ?
                """, String.class, tablespaceOf(NO_PK_TABLE));

        // InnoDB is an index-organised table: there is always a clustered index. With no primary
        // key and no non-null unique key it invents one over the hidden 6-byte DB_ROW_ID.
        assertThat(indexName).isEqualTo("GEN_CLUST_INDEX");

        // And the hidden column is not addressable from SQL, which is why it cannot be used
        // as a business key the way a real primary key can.
        assertThatThrownBy(() -> jdbc.queryForList("SELECT DB_ROW_ID FROM " + NO_PK_TABLE))
                .hasStackTraceContaining("Unknown column");
    }
}
