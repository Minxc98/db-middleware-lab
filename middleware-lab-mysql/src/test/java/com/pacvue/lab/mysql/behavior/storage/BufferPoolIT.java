package com.pacvue.lab.mysql.behavior.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 02 §4 - the buffer pool: where reads and writes actually happen, and the cold/hot split
 * that stops one table scan from throwing away everything useful.
 */
class BufferPoolIT extends AbstractBehaviorIT {

    @Override
    protected String table() {
        return "lab_pool_order";
    }

    @Test
    @DisplayName("the LRU list is split, with new pages landing in the cold 3/8")
    void lruIsSplitIntoYoungAndOld() {
        // docker-compose.yml pins the pool at 256M so these numbers are stable.
        assertThat(variable("innodb_buffer_pool_size")).isEqualTo("268435456");

        // 37% is the cold (old) sublist - the 3/8 from the notes. A page read in for the first
        // time is inserted at the head of *this* region, not at the head of the whole list.
        assertThat(variable("innodb_old_blocks_pct")).isEqualTo("37");

        // ...and it has to survive here for a second before a second touch can promote it.
        // A table scan touches each page once in quick succession, so its pages never qualify.
        assertThat(variable("innodb_old_blocks_time")).isEqualTo("1000");
    }

    @Test
    @DisplayName("reads are served from the pool, not the disk")
    void readsAreServedFromThePool() {
        insertSequentialOrders(20_000);

        long logicalBefore = globalStatus("Innodb_buffer_pool_read_requests");
        long physicalBefore = globalStatus("Innodb_buffer_pool_reads");

        jdbc.queryForObject("SELECT COUNT(*) FROM " + table() + " WHERE remark LIKE '%42%'",
                Long.class);

        long logical = globalStatus("Innodb_buffer_pool_read_requests") - logicalBefore;
        long physical = globalStatus("Innodb_buffer_pool_reads") - physicalBefore;

        // A full scan of 20k rows asks for a lot of pages...
        assertThat(logical).as("logical page reads during a full scan").isGreaterThan(100);
        // ...but the writes that put them there left them in the pool, so almost none of those
        // requests reach the disk. This ratio is the "buffer pool hit rate" in every dashboard.
        assertThat(physical)
                .as("physical reads %s out of %s logical", physical, logical)
                .isLessThan(logical / 10);
    }

    @Test
    @DisplayName("the pool reports how much of itself is data, and how much is still free")
    void poolAccountsForItsOwnPages() {
        insertSequentialOrders(20_000);

        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT POOL_SIZE, DATABASE_PAGES, OLD_DATABASE_PAGES, FREE_BUFFERS,
                       PAGES_MADE_YOUNG, PAGES_NOT_MADE_YOUNG, NUMBER_PAGES_READ_AHEAD
                FROM information_schema.INNODB_BUFFER_POOL_STATS
                """);

        long poolPages = ((Number) stats.get("POOL_SIZE")).longValue();
        long dataPages = ((Number) stats.get("DATABASE_PAGES")).longValue();
        long oldPages = ((Number) stats.get("OLD_DATABASE_PAGES")).longValue();
        long freePages = ((Number) stats.get("FREE_BUFFERS")).longValue();

        // 256M / 16K = 16384 pages - less the one the pool spends on its own control block.
        long nominalPages = 256L * 1024 * 1024 / 16384;
        assertThat(poolPages).isBetween(nominalPages - 16, nominalPages);
        assertThat(dataPages).isPositive();
        assertThat(dataPages + freePages).isLessThanOrEqualTo(poolPages);

        // The cold sublist is a real, countable part of the list, not a metaphor - and InnoDB
        // holds it at innodb_old_blocks_pct of the LRU list, not of the whole pool. With 2000
        // pages of data and 14000 still free, roughly 760 of those 2000 are the cold end.
        assertThat(oldPages)
                .as("old sublist %s of %s LRU pages", oldPages, dataPages)
                .isBetween(dataPages * 25 / 100, dataPages * 50 / 100);
        assertThat(((Number) stats.get("PAGES_NOT_MADE_YOUNG")).longValue())
                .as("pages that were touched but not promoted - what a table scan produces")
                .isNotNegative();
    }

    @Test
    @DisplayName("change buffering is on and covers every kind of secondary index write")
    void changeBufferingIsOnByDefault() {
        // 'all' = inserts, deletes and purges. It only ever applies to *non-unique* secondary
        // indexes: a unique index has to read the page anyway to check uniqueness, which is the
        // one thing change buffering is trying to avoid.
        assertThat(variable("innodb_change_buffering")).isEqualTo("all");

        // The merge counters exist but ship disabled - INNODB_METRICS is opt-in per counter.
        // Enabling them globally from a test would change the server for everything else
        // running against it, so this asserts they are there rather than reading them.
        Long ibufCounters = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.INNODB_METRICS WHERE NAME LIKE 'ibuf%'",
                Long.class);
        assertThat(ibufCounters).isPositive();
    }
}
