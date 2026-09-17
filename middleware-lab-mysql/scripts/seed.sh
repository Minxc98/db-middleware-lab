#!/usr/bin/env bash
# Fills lab_mysql.lab_order with enough rows that index choice and paging start to matter.
#
#   ./scripts/seed.sh            100000 rows
#   ./scripts/seed.sh 1000000    a million
#
# Rows are generated server-side with a recursive CTE, so nothing crosses the network per row.
set -euo pipefail

CONTAINER=${LAB_MYSQL_CONTAINER:-lab-mysql}
ROOT_PW=${LAB_MYSQL_ROOT_PASSWORD:-labroot}
DB=${LAB_MYSQL_DB:-lab_mysql}
ROWS=${1:-100000}

echo "==> seeding $DB.lab_order with $ROWS rows"

docker exec -i "$CONTAINER" mysql -uroot -p"$ROOT_PW" "$DB" 2>/dev/null <<SQL
DROP TABLE IF EXISTS lab_order;
CREATE TABLE lab_order (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(32)   NOT NULL,
    user_id     BIGINT        NOT NULL,
    status      TINYINT       NOT NULL,
    amount      DECIMAL(12,2) NOT NULL,
    remark      VARCHAR(255)  NULL,
    created_at  DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status_created (user_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The recursion limit defaults to 1000, which silently truncates the insert.
SET SESSION cte_max_recursion_depth = $((ROWS + 1));

INSERT INTO lab_order (order_no, user_id, status, amount, remark, created_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $ROWS)
SELECT CONCAT('NO-', LPAD(i, 9, '0')),
       i % 200,             -- 200 users, so user_id is selective but not unique
       i % 5,               -- 5 statuses, deliberately low cardinality
       ROUND(RAND(42) * 1000, 2),
       CONCAT('seeded row ', i),
       DATE_ADD('2026-01-01', INTERVAL i MINUTE)
FROM n;

-- Without this the optimizer works from stale statistics and picks odd plans.
ANALYZE TABLE lab_order;

SELECT TABLE_ROWS AS approx_rows,
       ROUND(DATA_LENGTH / 1024 / 1024, 1)  AS data_mb,
       ROUND(INDEX_LENGTH / 1024 / 1024, 1) AS index_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = '$DB' AND TABLE_NAME = 'lab_order';
SQL

echo "==> done. Things worth trying now:"
echo "    ./scripts/mysql-inspect.sh disk indexes"
echo "    docker exec -it $CONTAINER mysql -uroot -p$ROOT_PW $DB -e \\"
echo "      \"EXPLAIN ANALYZE SELECT * FROM lab_order ORDER BY id LIMIT 90000, 10\""
