#!/usr/bin/env bash
# Shared helpers for the demo and admin scripts. Uses only curl, jq and docker.

set -euo pipefail

# The controller as seen from the host; override to run against another address.
CONTROLLER="${CONTROLLER:-http://localhost:8080}"

# Must match Chunking.CHUNK_SIZE in the common module (decision 0001).
CHUNK_SIZE=1000000

# Prints a section heading.
step() {
  printf '\n==> %s\n' "$*"
}

# Prints a message and exits non-zero, so a demo fails loudly.
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

# GETs a controller path and prints the JSON body; fails on any non-2xx answer.
api() {
  curl -fsS --connect-timeout 1 --max-time 10 "$CONTROLLER$1"
}

# Prints the SHA-256 of a file; macOS has shasum, Linux has sha256sum.
sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Prints a check's result and fails the script if it did not hold.
check() {
  local description="$1"
  shift
  if "$@"; then
    printf '  PASS  %s\n' "$description"
  else
    fail "$description"
  fi
}

# Uploads a file and prints the response JSON; fails on any non-201 answer.
upload() {
  local file="$1"
  shift
  curl -fsS --max-time 300 "$@" -F "file=@$file" "$CONTROLLER/api/v1/objects"
}

# Repeats a check every second until it holds, failing after a timeout in seconds.
wait_for() {
  local description="$1" timeout="$2"
  shift 2
  local start=$SECONDS
  until "$@" 2>/dev/null; do
    if (( SECONDS - start >= timeout )); then
      fail "timed out after ${timeout}s waiting for: $description"
    fi
    sleep 1
  done
  printf '  PASS  %s (after %ss)\n' "$description" "$((SECONDS - start))"
}

# Prints a worker's state as the controller sees it.
worker_state() {
  api /internal/workers | jq -r --arg w "$1" '.workers[] | select(.worker_id == $w) | .state'
}

# Succeeds when a worker is in a state; re-reads it on every call, so wait_for can poll it.
state_is() {
  [ "$(worker_state "$1")" = "$2" ]
}

# Prints the replication summary on one line.
replication_summary() {
  api /internal/replication | jq -c '{total_chunks, healthy, under_replicated, unavailable, pending_tombstones}'
}

# Succeeds when no chunk is under-replicated or unavailable.
fully_replicated() {
  api /internal/replication | jq -e '.under_replicated == 0 and .unavailable == 0' >/dev/null
}

# A temp directory removed when the script exits.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
