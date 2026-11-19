#!/usr/bin/env bash
# Demo 2: an object is split into CHUNK_SIZE chunks; the last may be smaller.

source "$(dirname "$0")/lib.sh"

remainder=123456
size=$((5 * CHUNK_SIZE + remainder))
# Copies per worker before the upload: placement sends new copies to the least-loaded workers.
load_before="$(api /internal/workers | jq -c '[.workers[] | {(.worker_id): .healthy_replicas}] | add')"

step "Upload 5 x CHUNK_SIZE + $remainder = $size bytes (CHUNK_SIZE = $CHUNK_SIZE)"
file="$WORK_DIR/data.bin"
head -c "$size" /dev/urandom > "$file"
id="$(upload "$file" | jq -r .object_id)"
echo "  object_id $id"

step "Its chunks: GET /api/v1/objects/$id/metadata"
metadata="$(api "/api/v1/objects/$id/metadata")"
echo "$metadata" | jq -r '
  ["INDEX", "CHUNK_ID", "SIZE", "WORKERS"],
  (.chunks[] | [.index, .chunk_id, .size, ([.replicas[].worker_id] | join(","))])
  | @tsv' | column -t | sed 's/^/  /'

step "Checks"
check "6 chunks" [ "$(echo "$metadata" | jq .chunk_count)" = 6 ]
check "chunks 0-4 are exactly CHUNK_SIZE bytes" \
  [ "$(echo "$metadata" | jq --argjson c "$CHUNK_SIZE" '[.chunks[0:5][] | .size == $c] | all')" = true ]
check "the last chunk holds the $remainder-byte remainder" [ "$(echo "$metadata" | jq '.chunks[5].size')" = "$remainder" ]
check "chunk indexes are 0..5 in order" [ "$(echo "$metadata" | jq -c '[.chunks[].index]')" = "[0,1,2,3,4,5]" ]
check "every chunk ID is <object_id>-<index:06>" \
  [ "$(echo "$metadata" | jq --arg id "$id" '[.chunks[] | .chunk_id == ($id + "-" + ("00000" + (.index|tostring)))] | all')" = true ]
check "chunk sizes add up to the object size" [ "$(echo "$metadata" | jq '[.chunks[].size] | add')" = "$size" ]
check "each chunk's 2 copies are on 2 different workers" \
  [ "$(echo "$metadata" | jq '[.chunks[] | [.replicas[].worker_id] | (length == 2 and (unique | length) == 2)] | all')" = true ]
# Fewest copies first: on a balanced cluster that means all 4 workers; after a failure it means
# the emptiest ones (docs/architecture.md, placement).
used="$(echo "$metadata" | jq -c '[.chunks[].replicas[].worker_id] | unique')"
echo "  copies per worker before the upload: $load_before"
echo "  workers used: $used"
check "the copies went to the least-loaded workers" \
  [ "$(echo "$load_before" | jq --argjson used "$used" '
      (to_entries | sort_by(.value) | map(.key)) as $order
      | [$used[] as $w | ($order | index($w))] | max < ($used | length)')" = true ]

echo; echo "Demo 2 passed."
