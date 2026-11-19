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

# A temp directory removed when the script exits.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
