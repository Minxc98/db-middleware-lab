#!/usr/bin/env bash
# Drives the failure scenarios the test suite cannot automate: killing nodes, restarting
# them, and cutting one off the network. Run from the module directory, with the 3-node
# cluster up (docker compose -f docker-compose.cluster.yml up -d).
#
#   ./scripts/cluster-chaos.sh status
#   ./scripts/cluster-chaos.sh kill es02        # stop a node
#   ./scripts/cluster-chaos.sh start es02       # bring it back
#   ./scripts/cluster-chaos.sh restart es03     # for peer recovery
#   ./scripts/cluster-chaos.sh partition es03   # disconnect from the network (split brain)
#   ./scripts/cluster-chaos.sh heal es03        # reconnect
#   ./scripts/cluster-chaos.sh watch            # health + shards, refreshed every 2s
set -uo pipefail

ES_URL="${ES_URL:-http://localhost:9201}"
NETWORK="lab-es-cluster_default"
COMPOSE="docker compose -f docker-compose.cluster.yml"

usage() { sed -n '2,13p' "$0"; exit 1; }
[ $# -ge 1 ] || usage

node_ok() {
  case "${1:-}" in
    es01|es02|es03) return 0 ;;
    *) echo "unknown node: '${1:-}' (es01|es02|es03)" >&2; exit 2 ;;
  esac
}

case "$1" in
  status)
    echo "--- nodes ---";  curl -s "$ES_URL/_cat/nodes?v&h=name,node.role,master,heap.percent"
    echo "--- health ---"; curl -s "$ES_URL/_cat/health?v"
    echo "--- shards ---"; curl -s "$ES_URL/_cat/shards?v&h=index,shard,prirep,state,node"
    ;;
  kill)
    node_ok "${2:-}"; $COMPOSE stop "$2"
    echo "stopped $2. Watch the replica take over:"
    echo "  curl '$ES_URL/_cat/shards?v'   # prirep flips r -> p on a surviving node"
    echo "  curl '$ES_URL/_cat/health?v'   # yellow: no spare node left for the replica"
    ;;
  start)
    node_ok "${2:-}"; $COMPOSE start "$2"
    echo "started $2. The missing replica is rebuilt; health goes back to green."
    ;;
  restart)
    node_ok "${2:-}"; $COMPOSE restart "$2"
    echo "restarted $2. Peer recovery in progress:"
    echo "  curl '$ES_URL/_cat/recovery?v&active_only=true'   # stage: INDEX -> TRANSLOG -> DONE"
    ;;
  partition)
    node_ok "${2:-}"; docker network disconnect "$NETWORK" "lab-$2"
    echo "disconnected lab-$2 from $NETWORK."
    echo "  majority side (still serving):  curl '$ES_URL/_cat/health?v'"
    echo "  minority side (no quorum):      curl 'http://localhost:920X/_cluster/health'"
    echo "  -> expect master_not_discovered_exception there, and NO second master anywhere"
    ;;
  heal)
    node_ok "${2:-}"; docker network connect "$NETWORK" "lab-$2"
    echo "reconnected lab-$2; it rejoins and catches up."
    ;;
  watch)
    while true; do
      clear
      date
      curl -s "$ES_URL/_cat/health?v"
      curl -s "$ES_URL/_cat/shards?v&h=index,shard,prirep,state,node"
      sleep 2
    done
    ;;
  *) usage ;;
esac
