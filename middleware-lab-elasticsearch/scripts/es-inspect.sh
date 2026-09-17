#!/usr/bin/env bash
# Quick look at a running lab Elasticsearch: cluster state, disk, index files, container usage.
#
#   ./scripts/es-inspect.sh            # everything
#   ./scripts/es-inspect.sh disk files # just those sections
#
# ES_URL   override the endpoint     (default http://localhost:9200)
# ES_CTR   override the container    (default lab-elasticsearch)
set -uo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
ES_CTR="${ES_CTR:-lab-elasticsearch}"
ES_DATA="/usr/share/elasticsearch/data"

section() { printf '\n\033[1;36m== %s ==\033[0m\n' "$1"; }
note()    { printf '\033[0;33m%s\033[0m\n' "$1"; }
es()      { curl -fsS "${ES_URL}$1" || note "request failed: $1"; }

require_curl() {
  command -v curl >/dev/null || { echo "curl not found" >&2; exit 1; }
}

require_container() {
  command -v docker >/dev/null || { note "docker not found - skipping"; return 1; }
  docker ps --format '{{.Names}}' | grep -qx "$ES_CTR" || {
    note "container '$ES_CTR' is not running - skipping"; return 1; }
}

cmd_health() {
  section "cluster health"
  es "/_cluster/health?pretty"
  section "nodes (heap / cpu / load / role)"
  es "/_cat/nodes?v&h=name,version,node.role,heap.percent,ram.percent,cpu,load_1m,uptime"
  section "pending tasks / unassigned shards"
  es "/_cat/pending_tasks?v"
  es "/_cluster/allocation/explain?pretty" 2>/dev/null | head -30
}

cmd_disk() {
  section "disk allocation per node"
  es "/_cat/allocation?v&h=shards,disk.indices,disk.used,disk.avail,disk.total,disk.percent,node"
  section "filesystem stats (what the watermarks are measured against)"
  es "/_nodes/stats/fs?pretty&filter_path=nodes.*.fs.total,nodes.*.fs.data"
  section "watermark settings in effect"
  es "/_cluster/settings?include_defaults=true&filter_path=**.routing.allocation.disk&pretty"
  note "low 85% -> warn | high 90% -> shards relocate away | flood 95% -> indices set read-only"
}

cmd_indices() {
  section "indices by store size"
  es "/_cat/indices?v&s=store.size:desc&h=health,status,index,pri,rep,docs.count,docs.deleted,store.size,pri.store.size"
  section "shards"
  es "/_cat/shards?v&s=store:desc&h=index,shard,prirep,state,docs,store,node"
}

cmd_segments() {
  section "segments (merge pressure, deleted docs still on disk)"
  es "/_cat/segments?v&h=index,shard,prirep,segment,generation,docs.count,docs.deleted,size,size.memory,committed,searchable"
  section "per-index store + translog + merge totals"
  es "/_stats/store,translog,merge,refresh,flush?pretty&filter_path=indices.*.total.store,indices.*.total.translog,indices.*.total.merges.total_time_in_millis,indices.*.total.refresh.total"
}

cmd_files() {
  require_container || return 0
  section "data directory usage inside the container"
  docker exec "$ES_CTR" du -sh "$ES_DATA" 2>/dev/null
  docker exec "$ES_CTR" df -h "$ES_DATA"

  section "layout: data/indices/<index-uuid>/<shard>/{index,translog,_state}"
  docker exec "$ES_CTR" find "$ES_DATA/indices" -maxdepth 3 -type d 2>/dev/null | head -40

  section "biggest files on disk (Lucene segment files)"
  # .cfs compound segment, .fdt stored fields, .dvd doc values, .tim/.tip terms, .tlog translog
  docker exec "$ES_CTR" sh -c \
    "find $ES_DATA -type f -printf '%s\t%p\n' 2>/dev/null | sort -rn | head -20 | awk '{printf \"%10.2f MB  %s\n\", \$1/1048576, \$2}'"

  section "index uuid -> index name"
  es "/_cat/indices?v&h=index,uuid,pri,rep,store.size"
}

cmd_stats() {
  require_container || return 0
  section "container resource usage"
  docker stats --no-stream --format \
    'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.BlockIO}}'
  section "container state"
  docker ps --filter "name=lab-" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
  section "docker volume"
  # Read the volume off the container itself, so renaming it in compose cannot break this.
  local vol
  vol="$(docker inspect "$ES_CTR" --format '{{range .Mounts}}{{if eq .Type "volume"}}{{.Name}}{{end}}{{end}}' 2>/dev/null)"
  if [ -n "$vol" ]; then
    docker volume inspect "$vol" --format 'name={{.Name}} driver={{.Driver}} mountpoint={{.Mountpoint}}' 2>/dev/null
  else
    note "no named volume mounted (data would die with the container)"
  fi
}

cmd_jvm() {
  section "jvm heap / gc / threads"
  es "/_nodes/stats/jvm?pretty&filter_path=nodes.*.jvm.mem.heap_used_percent,nodes.*.jvm.mem.heap_used_in_bytes,nodes.*.jvm.mem.heap_max_in_bytes,nodes.*.jvm.gc,nodes.*.jvm.threads"
  section "thread pools with queued or rejected work"
  es "/_cat/thread_pool?v&h=node_name,name,active,queue,rejected&s=rejected:desc,queue:desc" | head -20
  section "circuit breakers"
  es "/_nodes/stats/breaker?pretty&filter_path=nodes.*.breaker.*.tripped,nodes.*.breaker.*.limit_size"
}

require_curl
targets=("$@")
[ ${#targets[@]} -eq 0 ] && targets=(health disk indices segments files stats jvm)

for t in "${targets[@]}"; do
  case "$t" in
    health|disk|indices|segments|files|stats|jvm) "cmd_$t" ;;
    *) echo "unknown section: $t (health disk indices segments files stats jvm)" >&2; exit 2 ;;
  esac
done
echo
