package com.pacvue.lab.mysql.behavior.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.domain.ExplainRow;
import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 03 §2/§3 - the clustered index holds the rows; a secondary index holds
 * {@code (indexed columns, primary key)} and therefore usually has to go back for the rest.
 */
class ClusteredIndexIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_idx_clustered";
    }

    @BeforeEach
    void seed() {
        insertSequentialOrders(5_000);
        analyze();
    }

    @Test
    @DisplayName("a secondary index leaf carries the primary key - even though nobody declared it")
    void secondaryIndexLeafCarriesThePrimaryKey() {
        // uk_order_no is declared as (order_no). Nothing in the DDL mentions id.
        ExplainRow plan = explain.explainFirst(
                "SELECT id FROM " + table() + " WHERE order_no = 'NO-000000123'");

        // Yet asking for id is covered by that index: no trip to the clustered index at all.
        // The only way that works is if the leaf entry physically contains the primary key -
        // which is exactly how the secondary index points at the row in the first place.
        assertThat(plan.key()).isEqualTo("uk_order_no");
        assertThat(plan.isCoveringIndex())
                .as("Extra was: %s", plan.extra())
                .isTrue();
    }

    @Test
    @DisplayName("asking for a non-indexed column forces the trip back to the clustered index")
    void nonIndexedColumnsForceALookup() {
        ExplainRow covered = explain.explainFirst(
                "SELECT id, order_no FROM " + table() + " WHERE order_no = 'NO-000000123'");
        ExplainRow notCovered = explain.explainFirst(
                "SELECT id, order_no, amount FROM " + table() + " WHERE order_no = 'NO-000000123'");

        assertThat(covered.isCoveringIndex()).isTrue();
        // amount lives only in the row, and the row lives only in the clustered index.
        assertThat(notCovered.isCoveringIndex())
                .as("Extra was: %s", notCovered.extra())
                .isFalse();
        // Same index, same seek - the difference is entirely the second B+ tree walk per row,
        // which EXPLAIN does not price and a benchmark does.
        assertThat(notCovered.key()).isEqualTo(covered.key());
    }

    @Test
    @DisplayName("a primary key lookup is the cheapest access there is - the row is right there")
    void primaryKeyLookupIsTheCheapestAccess() {
        ExplainRow plan = explain.explainFirst("SELECT * FROM " + table() + " WHERE id = 42");

        // const: the optimizer knows a unique index matched at most one row and reads it once,
        // before the rest of the plan is even considered.
        assertThat(plan.type()).isEqualTo("const");
        assertThat(plan.key()).isEqualTo("PRIMARY");
        assertThat(plan.rows()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the clustered index is the table: its size is the data size")
    void clusteredIndexIsTheTable() {
        analyze();

        // DATA_LENGTH is the clustered index - i.e. the rows. INDEX_LENGTH is every secondary
        // index put together. There is no third place where the data could be.
        var sizes = jdbc.queryForMap("""
                SELECT DATA_LENGTH, INDEX_LENGTH FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                """, table());

        long dataLength = ((Number) sizes.get("DATA_LENGTH")).longValue();
        long indexLength = ((Number) sizes.get("INDEX_LENGTH")).longValue();

        assertThat(dataLength).isPositive();
        assertThat(indexLength)
                .as("two secondary indexes over 5000 rows")
                .isPositive();

        // And InnoDB agrees about how many indexes there are: PRIMARY plus the two declared.
        Long indexes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.INNODB_INDEXES i
                JOIN information_schema.INNODB_TABLES t ON t.TABLE_ID = i.TABLE_ID
                WHERE t.NAME = ?
                """, Long.class, tablespaceName());
        assertThat(indexes).isEqualTo(3L);
    }
}
