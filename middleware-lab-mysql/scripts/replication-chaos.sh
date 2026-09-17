#!/usr/bin/env bash
# Break the replication link on purpose and watch what happens.
#
#   ./scripts/replication-chaos.sh status        where things stand
#   ./scripts/replication-chaos.sh lag           stop the applier, write, show the backlog
#   ./scripts/replication-chaos.sh resume        start both threads again
#   ./scripts/replication-chaos.sh partition     cut the network between the two
#   ./scripts/replication-chaos.sh heal          reconnect it
#   ./scripts/replication-chaos.sh kill-source   stop the source container
#   ./scripts/replication-chaos.sh watch         follow the link, refreshing every 2s
#
# Needs docker-compose.replication.yml to be up.
set -euo pipefail

SOURCE=${LAB_MYSQL_SOURCE_CONTAINER:-lab-mysql-source}
REPLICA=${LAB_MYSQL_REPLICA_CONTAINER:-lab-mysql-replica}
NETWORK=${LAB_MYSQL_REPL_NETWORK:-lab-mysql-repl_default}
ROOT_PW=${LAB_MYSQL_ROOT_PASSWORD:-labroot}
DB=lab_mysql_it

src() { docker exec -i "$SOURCE"  mysql -uroot -p"$ROOT_PW" -e "$1" 2>/dev/null; }
rep() { docker exec -i "$REPLICA" mysql -uroot -p"$ROOT_PW" -e "$1" 2>/dev/null; }

status() {
  echo "--- replica ---"
  rep "SHOW REPLICA STATUS\G" | grep -E \
    "Source_Host|Replica_IO_Running|Replica_SQL_Running:|Seconds_Behind_Source|Last_IO_Error|Last_SQL_Error|Retrieved_Gtid_Set|Executed_Gtid_Set" || true

  # Seconds_Behind_Source is NULL whenever the applier is stopped, so it cannot tell you about
  # the worst case. This can: what has been received minus what has been executed.
  echo "--- backlog (received minus executed) ---"
  rep "SELECT GTID_SUBTRACT(
         (SELECT RECEIVED_TRANSACTION_SET FROM performance_schema.replication_connection_status),
         @@global.gtid_executed) AS not_yet_applied\G" || true

  echo "--- semi-sync on the source ---"
  src "SHOW GLOBAL STATUS WHERE Variable_name IN
       ('Rpl_semi_sync_source_status','Rpl_semi_sync_source_clients',
        'Rpl_semi_sync_source_yes_tx','Rpl_semi_sync_source_no_tx')"
}

case "${1:-status}" in
  status)
    status
    ;;

  lag)
    echo "==> stopping the apply thread and writing on the source"
    rep "STOP REPLICA SQL_THREAD"
    src "CREATE DATABASE IF NOT EXISTS $DB;
         CREATE TABLE IF NOT EXISTS $DB.chaos (id INT AUTO_INCREMENT PRIMARY KEY, at DATETIME(3));
         INSERT INTO $DB.chaos (at) VALUES (NOW(3)), (NOW(3)), (NOW(3));"
    sleep 2
    status
    echo "==> the rows are on the source and in the replica's relay log, but not applied."
    echo "    run '$0 resume' to let it catch up"
    ;;

  resume)
    rep "START REPLICA"
    sleep 2
    status
    ;;

  partition)
    echo "==> disconnecting $REPLICA from $NETWORK"
    docker network disconnect "$NETWORK" "$REPLICA"
    echo "==> the IO thread now retries on SOURCE_CONNECT_RETRY, and after"
    echo "    rpl_semi_sync_source_timeout the source degrades to asynchronous"
    ;;

  heal)
    docker network connect "$NETWORK" "$REPLICA"
    rep "STOP REPLICA; START REPLICA" || true
    sleep 3
    status
    ;;

  kill-source)
    docker stop "$SOURCE"
    echo "==> source is down. The replica keeps serving reads. Promoting it would be:"
    echo "      STOP REPLICA; RESET REPLICA ALL;"
    echo "      SET GLOBAL read_only = 0; SET GLOBAL super_read_only = 0;"
    echo "    and every other replica would then be repointed at it - which with GTIDs needs"
    echo "    no file name and no offset, only SOURCE_AUTO_POSITION = 1."
    ;;

  watch)
    while true; do clear; date; status; sleep 2; done
    ;;

  *)
    echo "unknown command: ${1:-}" >&2
    sed -n '2,15p' "$0"
    exit 2
    ;;
esac
