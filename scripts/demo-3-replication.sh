#!/usr/bin/env bash
# Demo 3: every chunk has 2 copies on different workers, and each copy is a file in that worker's
# volume.

source "$(dirname "$0")/lib.sh"

step "Upload a 3-chunk object"
file="$WORK_DIR/data.bin"
head -c $((2 * CHUNK_SIZE + 500000)) /dev/urandom > "$file"
id="$(upload "$file" | jq -r .object_id)"
echo "  object_id $id"
metadata="$(api "/api/v1/objects/$id/metadata")"

step "Each chunk's copies, and the file on each worker's volume (docker exec)"
for chunk in $(echo "$metadata" | jq -r '.chunks[].chunk_id'); do
  copies="$(echo "$metadata" | jq -c --arg c "$chunk" '.chunks[] | select(.chunk_id == $c) | .replicas')"
  workers="$(echo "$copies" | jq -r '.[].worker_id')"
  echo "  chunk $chunk"
  check "  2 copies" [ "$(echo "$copies" | jq length)" = 2 ]
  check "  both HEALTHY" [ "$(echo "$copies" | jq '[.[].status == "HEALTHY"] | all')" = true ]
  check "  on 2 different workers" [ "$(echo "$workers" | sort -u | wc -l | tr -d ' ')" = 2 ]
  for worker in $workers; do
    path="$(docker exec "$worker" find /data/chunks -name "$chunk.*" ! -name '*.corrupt' ! -name '*.tmp')"
    size="$(docker exec "$worker" stat -c %s "$path" 2>/dev/null || echo missing)"
    echo "      $worker:$path ($size bytes)"
    check "  file present on $worker" [ -n "$path" ]
  done
done

step "The workers' own inventories agree with the metadata"
for worker in worker1 worker2 worker3 worker4; do
  expected="$(api "/internal/workers/$worker/chunks?view=expected" | jq -c '[.chunks[].chunk_id] | sort')"
  live="$(api "/internal/workers/$worker/chunks" | jq -c '[.chunks[].chunk_id] | sort')"
  check "$worker holds exactly what the metadata says ($(echo "$live" | jq length) chunks)" [ "$expected" = "$live" ]
done

echo; echo "Demo 3 passed."
