package com.pacvue.lab.mysql.behavior.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.pacvue.lab.mysql.support.AbstractBehaviorIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notes 01 - the two layers: a Server layer (connection, parser, optimizer, executor, binlog)
 * sitting on top of a pluggable storage engine.
 *
 * <p>The division is not just a diagram: it is observable. The binlog belongs to the Server
 * layer, so it records writes to a MyISAM table that has no redo log, no undo log and no
 * buffer pool of its own.
 */
class ServerArchitectureIT extends AbstractBehaviorIT {

    private static final String MYISAM_TABLE = "lab_arch_myisam";

    @Override
    protected String table() {
        return "lab_arch_order";
    }

    @Override
    protected void createTable() {
        super.createTable();
        jdbc.execute("DROP TABLE IF EXISTS " + MYISAM_TABLE);
        jdbc.execute("CREATE TABLE " + MYISAM_TABLE
                + " (id INT PRIMARY KEY, note VARCHAR(50)) ENGINE=MyISAM");
    }

    @Test
    @DisplayName("the query cache is gone in 8.0 - not off, gone")
    void queryCacheIsGone() {
        List<Map<String, Object>> variables =
                jdbc.queryForList("SHOW VARIABLES LIKE 'query_cache%'");

        // In 5.7 this returned query_cache_type / query_cache_size / have_query_cache.
        // 8.0 removed the feature outright, so there is no variable left to switch.
        assertThat(variables).isEmpty();
    }

    @Test
    @DisplayName("InnoDB is the default engine, and it is the one with transactions")
    void innodbIsTheDefaultEngine() {
        List<Map<String, Object>> engines = jdbc.queryForList(
                "SELECT ENGINE, SUPPORT, TRANSACTIONS, XA, SAVEPOINTS"
                        + " FROM information_schema.ENGINES WHERE ENGINE IN ('InnoDB', 'MyISAM')");

        Map<String, Object> innodb = engines.stream()
                .filter(row -> "InnoDB".equals(row.get("ENGINE"))).findFirst().orElseThrow();
        Map<String, Object> myisam = engines.stream()
                .filter(row -> "MyISAM".equals(row.get("ENGINE"))).findFirst().orElseThrow();

        assertThat(innodb.get("SUPPORT")).isEqualTo("DEFAULT");
        assertThat(innodb.get("TRANSACTIONS")).isEqualTo("YES");
        assertThat(innodb.get("SAVEPOINTS")).isEqualTo("YES");

        assertThat(myisam.get("SUPPORT")).isEqualTo("YES");
        assertThat(myisam.get("TRANSACTIONS")).isEqualTo("NO");
        assertThat(myisam.get("SAVEPOINTS")).isEqualTo("NO");
    }

    @Test
    @DisplayName("the binlog is Server-layer: it records MyISAM writes too")
    void binlogIsServerLayerNotEngineLayer() {
        // redo/undo are InnoDB's and MyISAM has neither. If the binlog belonged to the engine,
        // a MyISAM write could not appear in it - and replication of MyISAM tables would be
        // impossible, which it is not.
        long before = binlogPosition();

        jdbc.update("INSERT INTO " + MYISAM_TABLE + " (id, note) VALUES (1, 'written to MyISAM')");

        assertThat(binlogPosition())
                .as("binlog position after a MyISAM insert")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("the optimizer picks the plan, and says so before the executor runs anything")
    void optimizerProducesAPlanBeforeExecution() {
        insertSequentialOrders(200);
        analyze();

        // EXPLAIN stops after the optimizer: it reports the chosen plan without asking the
        // engine for a single row. Which is why it is free to run against production.
        var plan = explain.explainFirst("SELECT * FROM " + table() + " WHERE id = 7");

        assertThat(plan.type()).isEqualTo("const");   // primary key, single row
        assertThat(plan.key()).isEqualTo("PRIMARY");
        assertThat(plan.rows()).isEqualTo(1L);
    }

    private long binlogPosition() {
        Map<String, Object> status = jdbc.queryForMap("SHOW MASTER STATUS");
        return ((Number) status.get("Position")).longValue();
    }
}
