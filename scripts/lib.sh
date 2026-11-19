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
