#!/usr/bin/env bash
# What is this MySQL actually doing?
#
#   ./scripts/mysql-inspect.sh                 everything
#   ./scripts/mysql-inspect.sh disk locks      just those sections
#
# Sections: status disk tables indexes locks binlog innodb
set -euo pipefail

CONTAINER=${LAB_MYSQL_CONTAINER:-lab-mysql}
ROOT_PW=${LAB_MYSQL_ROOT_PASSWORD:-labroot}
DB=${LAB_MYSQL_DB:-lab_mysql}

q() { docker exec -i "$CONTAINER" mysql -uroot -p"$ROOT_PW" --table -e "$1" 2>/dev/null; }
qv() { docker exec -i "$CONTAINER" mysql -uroot -p"$ROOT_PW" --vertical -e "$1" 2>/dev/null; }
title() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }

section_status() {
  title "server"
  q "SELECT VERSION() AS version, @@server_id AS server_id, @@transaction_isolation AS isolation,
            @@innodb_buffer_pool_size/1024/1024 AS buffer_pool_mb, @@max_connections AS max_conn"

  title "connections and throughput"
  q "SHOW GLOBAL STATUS WHERE Variable_name IN
       ('Threads_connected','Threads_running','Aborted_connects','Max_used_connections',
        'Queries','Slow_queries','Com_select','Com_insert','Com_update','Com_delete')"

  title "who is connected right now"
  q "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, LEFT(INFO, 60) AS INFO
     FROM information_schema.PROCESSLIST ORDER BY TIME DESC LIMIT 15"
}

section_disk() {
  title "disk used by the data directory"
  docker exec -i "$CONTAINER" sh -c 'du -sh /var/lib/mysql 2>/dev/null; df -h /var/lib/mysql | tail -1'

  title "the twenty largest files"
  docker exec -i "$CONTAINER" sh -c \
    'find /var/lib/mysql -type f -printf "%10s  %p\n" 2>/dev/null | sort -rn | head -20'

  title "space per table (data = clustered index, index = everything else)"
  q "SELECT TABLE_NAME,
            TABLE_ROWS AS approx_rows,
            ROUND(DATA_LENGTH/1024/1024, 2)  AS data_mb,
            ROUND(INDEX_LENGTH/1024/1024, 2) AS index_mb,
            ROUND(DATA_FREE/1024/1024, 2)    AS free_mb
     FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = '$DB' ORDER BY DATA_LENGTH + INDEX_LENGTH DESC LIMIT 20"

  title "the volume on the host"
  docker volume inspect lab-mysql-data --format '{{.Mountpoint}}' 2>/dev/null || true
}

section_tables() {
  title "tables in $DB"
  q "SELECT TABLE_NAME, ENGINE, ROW_FORMAT, TABLE_ROWS, AUTO_INCREMENT, CREATE_TIME
     FROM information_schema.TABLES WHERE TABLE_SCHEMA = '$DB'"

  title "tablespace files"
  q "SELECT s.NAME, d.PATH, ROUND(s.FILE_SIZE/1024/1024, 2) AS file_mb,
            ROUND(s.ALLOCATED_SIZE/1024/1024, 2) AS allocated_mb, s.ROW_FORMAT
     FROM information_schema.INNODB_TABLESPACES s
     JOIN information_schema.INNODB_DATAFILES d ON d.SPACE = s.SPACE
     WHERE s.NAME LIKE '$DB/%' ORDER BY s.FILE_SIZE DESC LIMIT 20"
}

section_indexes() {
  title "indexes and their selectivity"
  # CARDINALITY is an estimate. Close to TABLE_ROWS means highly selective; close to 1 means the
  # index barely narrows anything down and may not be earning the write cost it imposes.
  q "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, CARDINALITY
     FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = '$DB'
     ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX"

  title "indexes nobody has used since the server started"
  # Empty output is not proof an index is dead - only that it was unused since startup.
  q "SELECT object_name, index_name FROM sys.schema_unused_indexes WHERE object_schema = '$DB'"

  title "statements doing full table scans"
  q "SELECT LEFT(query, 80) AS query, exec_count, rows_examined_avg, rows_sent_avg
     FROM sys.statements_with_full_table_scans WHERE db = '$DB' LIMIT 10"
}

section_locks() {
  title "transactions currently open"
  q "SELECT trx_id, trx_state, trx_started,
            TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_s,
            trx_mysql_thread_id AS conn, trx_rows_locked, trx_rows_modified,
            LEFT(trx_query, 50) AS query
     FROM information_schema.innodb_trx ORDER BY trx_started"

  title "row locks held and waited for"
  # LOCK_MODE is the interesting column: X,REC_NOT_GAP is a record lock, X,GAP a gap lock,
  # a bare X a next-key lock, X,GAP,INSERT_INTENTION an INSERT blocked by somebody's gap.
  q "SELECT OBJECT_NAME, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA, THREAD_ID
     FROM performance_schema.data_locks LIMIT 40"

  title "who is waiting for whom"
  q "SELECT * FROM sys.innodb_lock_waits LIMIT 10"

  title "metadata locks (needs the mdl instrument; compose turns it on)"
  q "SELECT OBJECT_SCHEMA, OBJECT_NAME, LOCK_TYPE, LOCK_STATUS, OWNER_THREAD_ID
     FROM performance_schema.metadata_locks
     WHERE OBJECT_SCHEMA NOT IN ('mysql','performance_schema') LIMIT 20"

  title "last deadlock"
  qv "SHOW ENGINE INNODB STATUS" | sed -n '/LATEST DETECTED DEADLOCK/,/^---/p' | head -40
}

section_binlog() {
  title "binlog files"
  q "SHOW BINARY LOGS"

  title "current position"
  q "SHOW MASTER STATUS"

  title "format and durability"
  q "SELECT @@log_bin AS log_bin, @@binlog_format AS format, @@binlog_row_image AS row_image,
            @@sync_binlog AS sync_binlog, @@gtid_mode AS gtid_mode,
            @@binlog_expire_logs_seconds AS expire_s"

  title "last events in the current file"
  # SHOW BINLOG EVENTS with no IN clause reads the FIRST file, which is rarely the one you want.
  local current
  current=$(docker exec -i "$CONTAINER" mysql -uroot -p"$ROOT_PW" -N -e "SHOW MASTER STATUS" 2>/dev/null | awk '{print $1}')
  q "SHOW BINLOG EVENTS IN '$current'" | tail -22
}

section_innodb() {
  title "buffer pool"
  q "SELECT POOL_SIZE AS pages, DATABASE_PAGES AS data_pages, OLD_DATABASE_PAGES AS cold_pages,
            FREE_BUFFERS AS free_pages, MODIFIED_DATABASE_PAGES AS dirty_pages,
            HIT_RATE, PAGES_MADE_YOUNG, PAGES_NOT_MADE_YOUNG
     FROM information_schema.INNODB_BUFFER_POOL_STATS"
  q "SHOW GLOBAL STATUS WHERE Variable_name IN
       ('Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads',
        'Innodb_buffer_pool_pages_dirty','Innodb_row_lock_waits','Innodb_row_lock_time_avg')"

  title "redo and checkpoint (the gap between them is written-but-not-flushed work)"
  qv "SHOW ENGINE INNODB STATUS" \
    | grep -E "Log sequence number|Log flushed up to|Last checkpoint at|History list length" || true

  title "undo history (growing and not shrinking means a long transaction is pinning it)"
  q "SELECT NAME, COUNT, STATUS FROM information_schema.INNODB_METRICS
     WHERE NAME = 'trx_rseg_history_len'"
}

SECTIONS=${*:-status disk tables indexes locks binlog innodb}
for section in $SECTIONS; do
  case "$section" in
    status)  section_status ;;
    disk)    section_disk ;;
    tables)  section_tables ;;
    indexes) section_indexes ;;
    locks)   section_locks ;;
    binlog)  section_binlog ;;
    innodb)  section_innodb ;;
    *) echo "unknown section: $section" >&2; exit 2 ;;
  esac
done
