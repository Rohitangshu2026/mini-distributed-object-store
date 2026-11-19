#!/usr/bin/env bash
# Demo 5: a worker fails and comes back. Objects stay downloadable while it is down, the lost copies
# are re-created on the other workers, and when it returns the cluster is reconciled back to
# exactly 2 copies of every chunk.

source "$(dirname "$0")/lib.sh"

victim="${1:-worker2}"

step "Before: upload a 3-chunk object; every chunk must have 2 copies"
file="$WORK_DIR/data.bin"
head -c $((3 * CHUNK_SIZE)) /dev/urandom > "$file"
id="$(upload "$file" | jq -r .object_id)"
echo "  object_id $id"
wait_for "the cluster is fully replicated" 60 fully_replicated
"$(dirname "$0")/cluster-status.sh" | sed 's/^/  /'
echo "  replication: $(replication_summary)"

step "docker stop $victim"
docker stop "$victim" >/dev/null
echo "  stopped at $(date '+%H:%M:%S')"

step "During: the object still downloads correctly"
curl -fsS -o "$WORK_DIR/during.bin" "$CONTROLLER/api/v1/objects/$id"
check "downloaded bytes match the original" [ "$(sha256 "$file")" = "$(sha256 "$WORK_DIR/during.bin")" ]
wait_for "$victim is SUSPECT (after 9 s without a heartbeat)" 30 state_is "$victim" SUSPECT
echo "  replication: $(replication_summary)"
wait_for "$victim is DEAD (after 15 s)" 30 state_is "$victim" DEAD
wait_for "repair restored 2 copies of every chunk on the other workers" 90 fully_replicated
echo "  replication: $(replication_summary)"
copies_on_victim="$(api "/internal/workers/$victim/chunks?view=expected" | jq '[.chunks[] | select(.status == "HEALTHY")] | length')"
check "no HEALTHY copy is counted on $victim" [ "$copies_on_victim" = 0 ]
"$(dirname "$0")/cluster-status.sh" | sed 's/^/  /'

step "docker start $victim"
docker start "$victim" >/dev/null
wait_for "$victim is ALIVE again" 60 state_is "$victim" ALIVE

step "After rejoin: reconciliation removes the extra copies"
live_copies() {
  local total=0
  for w in worker1 worker2 worker3 worker4; do
    total=$((total + $(api "/internal/workers/$w/chunks" | jq '.chunks | length')))
  done
  echo "$total"
}
exactly_two_copies() {
  local report
  report="$(api /internal/replication)"
  [ "$(echo "$report" | jq '.under_replicated + .unavailable + .pending_tombstones')" = 0 ] &&
    [ "$(live_copies)" = "$(( 2 * $(echo "$report" | jq .total_chunks) ))" ]
}
wait_for "every worker's disk holds exactly 2 x total_chunks files and no tombstone is pending" 120 exactly_two_copies
echo "  replication: $(replication_summary)"
echo "  live chunk files on all workers: $(live_copies)"
curl -fsS -o "$WORK_DIR/after.bin" "$CONTROLLER/api/v1/objects/$id"
check "the object still downloads correctly" [ "$(sha256 "$file")" = "$(sha256 "$WORK_DIR/after.bin")" ]
"$(dirname "$0")/cluster-status.sh" | sed 's/^/  /'

echo; echo "Demo 5 passed."
