#!/usr/bin/env bash
# Fills the lab index with synthetic products so there is something to look at:
# segment files on disk, terms to aggregate, enough docs for paging to matter.
#
#   ./scripts/seed.sh            # 40000 docs into lab-product
#   ./scripts/seed.sh 200000     # more, if you want merges and disk growth to show
#
# ES_URL    endpoint    (default http://localhost:9200)
# ES_INDEX  index name  (default lab-product)
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
ES_INDEX="${ES_INDEX:-lab-product}"
TOTAL="${1:-40000}"
BATCH=4000
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v curl >/dev/null || { echo "curl not found" >&2; exit 1; }

echo "recreating ${ES_URL}/${ES_INDEX}"
curl -fsS -XDELETE "${ES_URL}/${ES_INDEX}" >/dev/null 2>&1 || true
curl -fsS -XPUT "${ES_URL}/${ES_INDEX}" -H 'Content-Type: application/json' -d '{
  "settings": {"number_of_shards": 2, "number_of_replicas": 0, "refresh_interval": "1s"},
  "mappings": {"properties": {
    "title":       {"type": "text"},
    "brand":       {"type": "keyword"},
    "asin":        {"type": "keyword"},
    "price":       {"type": "double"},
    "updatedAt":   {"type": "date"},
    "description": {"type": "text"}
  }}
}' >/dev/null

echo "generating ${TOTAL} documents"
awk -v total="$TOTAL" 'BEGIN{
  split("Acme Globex Initech Umbrella Soylent", b, " ");
  split("Wireless Bluetooth Waterproof Portable Rechargeable Ergonomic Stainless Adjustable", w, " ");
  for (i = 1; i <= total; i++) {
    printf("{\"index\":{\"_id\":\"%d\"}}\n", i);
    t = w[(i%8)+1] " " w[((i*3)%8)+1] " Headphones Model " i;
    d = "";
    for (j = 0; j < 30; j++) d = d w[((i+j)%8)+1] " ";
    # Deliberately written without milliseconds: this is the format an "other service"
    # produces, and the mapping in ProductDoc has to cope with it.
    printf("{\"title\":\"%s\",\"brand\":\"%s\",\"asin\":\"B0%06d\",\"price\":%.2f,\"updatedAt\":\"2026-09-17T00:00:00Z\",\"description\":\"%s\"}\n",
           t, b[(i%5)+1], i, (i%500)+0.99, d);
  }
}' > "$WORK/bulk.ndjson"

split -l $((BATCH * 2)) "$WORK/bulk.ndjson" "$WORK/part."
for f in "$WORK"/part.*; do
  printf '.'
  curl -fsS -H 'Content-Type: application/x-ndjson' \
       -XPOST "${ES_URL}/${ES_INDEX}/_bulk" --data-binary "@$f" -o /dev/null
done
echo

curl -fsS -XPOST "${ES_URL}/${ES_INDEX}/_refresh" -o /dev/null
echo "done:"
curl -fsS "${ES_URL}/_cat/indices/${ES_INDEX}?v&h=index,docs.count,store.size,pri"
