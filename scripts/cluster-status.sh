#!/usr/bin/env bash
# Shows each worker's state as the controller sees it.
#
#   scripts/cluster-status.sh                                  print once
#   scripts/cluster-status.sh --watch                          print every second, with timestamps
#   scripts/cluster-status.sh --watch --until worker2=DEAD     stop when worker2 is DEAD
#                             [--timeout 60]                   (exit 1 if it takes longer)

source "$(dirname "$0")/lib.sh"

watch=false
until_worker=""
until_state=""
timeout=60
while [[ $# -gt 0 ]]; do
  case "$1" in
    --watch) watch=true ;;
    --until)
      until_worker="${2%%=*}"
      until_state="${2#*=}"
      shift
      ;;
    --timeout)
      timeout="$2"
      shift
      ;;
    *) fail "unknown argument: $1" ;;
  esac
  shift
done

print_status() {
  api /internal/workers | jq -r '
    ["WORKER", "STATE", "HEARTBEAT_AGE", "CHUNKS", "HEALTHY_REPLICAS"],
    (.workers[] | [.worker_id, .state, "\(.last_heartbeat_age_ms / 1000 | floor)s",
                   (.reported_chunk_count // "-"), .healthy_replicas])
    | @tsv' | column -t
}

state_of() {
  api /internal/workers | jq -r --arg w "$1" '.workers[] | select(.worker_id == $w) | .state'
}

if [[ "$watch" == false ]]; then
  print_status
  exit 0
fi

start=$SECONDS
while true; do
  printf '\n%s\n' "$(date '+%H:%M:%S')"
  print_status || echo "(controller not answering)"
  if [[ -n "$until_worker" && "$(state_of "$until_worker" 2>/dev/null || true)" == "$until_state" ]]; then
    printf '%s is %s after %ss\n' "$until_worker" "$until_state" "$((SECONDS - start))"
    exit 0
  fi
  if [[ -n "$until_worker" && $((SECONDS - start)) -ge $timeout ]]; then
    fail "$until_worker did not become $until_state within ${timeout}s"
  fi
  sleep 1
done
