#!/usr/bin/env bash
# Demo 6: a stored copy is corrupted inside a worker's volume. The download still returns the right
# bytes, the logs name the chunk and the worker, and repair restores 2 valid copies.

source "$(dirname "$0")/lib.sh"

step "Upload a 3-chunk object"
file="$WORK_DIR/data.bin"
head -c $((3 * CHUNK_SIZE)) /dev/urandom > "$file"
id="$(upload "$file" | jq -r .object_id)"
echo "  object_id $id"
wait_for "the cluster is fully replicated" 60 fully_replicated

# A download tries copies in worker order, so corrupt the copy it will read first.
metadata="$(api "/api/v1/objects/$id/metadata")"
chunk="$(echo "$metadata" | jq -r '.chunks[1].chunk_id')"
worker="$(echo "$metadata" | jq -r '.chunks[1].replicas | sort_by(.worker_id) | .[0].worker_id')"
since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

step "Corrupt chunk 1's copy on $worker"
"$(dirname "$0")/corrupt-chunk.sh" "$worker" "$chunk" | sed 's/^/  /'

step "Download: the corrupt copy is detected and never served"
curl -fsS -o "$WORK_DIR/downloaded.bin" "$CONTROLLER/api/v1/objects/$id"
check "downloaded bytes match the original" [ "$(sha256 "$file")" = "$(sha256 "$WORK_DIR/downloaded.bin")" ]

step "Logs name the chunk and the worker"
docker logs --since "$since" "$worker" 2>&1 | jq -r --arg c "$chunk" --arg w "$worker" \
  'select(.chunk_id? == $c) | "  \($w): \(.log.level) \(.message)"' 2>/dev/null
docker logs --since "$since" controller 2>&1 | jq -r --arg c "$chunk" \
  'select(.chunk_id? == $c) | "  controller: \(.log.level) [\(.worker_id)] \(.message)"' 2>/dev/null
check "the worker logged the quarantine" \
  sh -c "docker logs --since '$since' '$worker' 2>&1 | grep -q 'quarantined'"
check "the controller logged the corrupt copy with its worker" \
  sh -c "docker logs --since '$since' controller 2>&1 | jq -e --arg c '$chunk' --arg w '$worker' 'select(.chunk_id? == \$c and .worker_id? == \$w and (.message | test(\"corrupt\")))' >/dev/null 2>&1"

step "Repair: the corrupt copy is rewritten in place"
copy_is_healthy_again() {
  local copies
  copies="$(api "/api/v1/objects/$id/metadata" | jq -c --arg c "$chunk" '.chunks[] | select(.chunk_id == $c) | .replicas')"
  [ "$(echo "$copies" | jq '[.[] | select(.status == "HEALTHY")] | length')" = 2 ] &&
    [ "$(echo "$copies" | jq -r --arg w "$worker" '.[] | select(.worker_id == $w) | .status')" = HEALTHY ]
}
wait_for "chunk $chunk has 2 HEALTHY copies, including the rewritten one on $worker" 90 copy_is_healthy_again
inventory="$(api "/internal/workers/$worker/chunks")"
check "$worker serves the chunk again" \
  [ "$(echo "$inventory" | jq --arg c "$chunk" '[.chunks[] | select(.chunk_id == $c)] | length')" = 1 ]
check "the quarantined file is gone" \
  [ "$(echo "$inventory" | jq --arg c "$chunk" '[.quarantined[] | select(. == $c)] | length')" = 0 ]
wait_for "the cluster is fully replicated" 60 fully_replicated
echo "  replication: $(replication_summary)"
curl -fsS -o "$WORK_DIR/again.bin" "$CONTROLLER/api/v1/objects/$id"
check "the object still downloads correctly" [ "$(sha256 "$file")" = "$(sha256 "$WORK_DIR/again.bin")" ]

echo; echo "Demo 6 passed."
