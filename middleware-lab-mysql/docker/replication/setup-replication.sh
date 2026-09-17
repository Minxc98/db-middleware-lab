#!/usr/bin/env bash
# Wires the replica to the source. Run once by the `setup` service in
# docker-compose.replication.yml; safe to run again.
set -euo pipefail

SOURCE_HOST=source
REPLICA_HOST=replica
ROOT_PW=labroot
REPL_USER=repl
REPL_PW=replpw

source_sql() { mysql -h "$SOURCE_HOST"  -uroot -p"$ROOT_PW" --silent -e "$1"; }
replica_sql() { mysql -h "$REPLICA_HOST" -uroot -p"$ROOT_PW" --silent -e "$1"; }

echo "==> waiting for both servers"
for host in "$SOURCE_HOST" "$REPLICA_HOST"; do
  until mysqladmin ping -h "$host" -uroot -p"$ROOT_PW" --silent >/dev/null 2>&1; do sleep 2; done
done

echo "==> source: replication account + semi-sync plugin"
# mysql_native_password rather than caching_sha2: the replica connects without TLS, and
# caching_sha2 would need either TLS or GET_SOURCE_PUBLIC_KEY. Fine for a lab, not for anything
# reachable from outside it.
source_sql "
  CREATE USER IF NOT EXISTS '$REPL_USER'@'%' IDENTIFIED WITH mysql_native_password BY '$REPL_PW';
  GRANT REPLICATION SLAVE ON *.* TO '$REPL_USER'@'%';
  FLUSH PRIVILEGES;
  INSTALL PLUGIN rpl_semi_sync_source SONAME 'semisync_source.so';
" 2>/dev/null || true
# Separate statement: the plugin has to exist before its variables do, which is also why these
# cannot be passed as mysqld flags.
source_sql "
  SET GLOBAL rpl_semi_sync_source_enabled = 1;
  SET GLOBAL rpl_semi_sync_source_timeout = 3000;
"

echo "==> replica: semi-sync plugin"
replica_sql "
  INSTALL PLUGIN rpl_semi_sync_replica SONAME 'semisync_replica.so';
" 2>/dev/null || true
replica_sql "SET GLOBAL rpl_semi_sync_replica_enabled = 1;"

echo "==> replica: point at the source and start"
# SOURCE_AUTO_POSITION=1 is the GTID form: no binlog file name, no offset. The replica tells the
# source which GTIDs it already has and the source sends the rest. This is what makes failover
# survivable - a new source does not need anyone to work out a file and position.
replica_sql "
  STOP REPLICA;
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='$SOURCE_HOST',
    SOURCE_PORT=3306,
    SOURCE_USER='$REPL_USER',
    SOURCE_PASSWORD='$REPL_PW',
    SOURCE_AUTO_POSITION=1,
    SOURCE_CONNECT_RETRY=5;
  START REPLICA;
"

echo "==> replica: refuse writes from anyone, including SUPER"
replica_sql "SET GLOBAL read_only = 1; SET GLOBAL super_read_only = 1;"

echo "==> source: create the lab schema (it reaches the replica through replication)"
source_sql "CREATE DATABASE IF NOT EXISTS lab_mysql_it DEFAULT CHARACTER SET utf8mb4;"

echo "==> waiting for both replication threads"
for _ in $(seq 1 30); do
  io=$(replica_sql "SHOW REPLICA STATUS\G" | awk -F': *' '/Replica_IO_Running/{print $2}')
  sql=$(replica_sql "SHOW REPLICA STATUS\G" | awk -F': *' '/Replica_SQL_Running:/{print $2}')
  if [ "$io" = "Yes" ] && [ "$sql" = "Yes" ]; then
    echo "==> replication is running"
    replica_sql "SHOW REPLICA STATUS\G" | grep -E "Source_Host|Replica_IO_Running|Replica_SQL_Running:|Seconds_Behind_Source|Executed_Gtid_Set" || true
    exit 0
  fi
  sleep 2
done

echo "!! replication did not start; last status:"
replica_sql "SHOW REPLICA STATUS\G"
exit 1
