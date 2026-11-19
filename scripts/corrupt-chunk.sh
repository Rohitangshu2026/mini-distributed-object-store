#!/usr/bin/env bash
# Deliberately corrupts one stored copy by flipping a bit of its first byte inside a worker's
# volume, for the integrity demo. The worker only notices when it next reads or scrubs the file.
#
#   scripts/corrupt-chunk.sh WORKER [CHUNK_ID]    (default: the first chunk the worker holds)

source "$(dirname "$0")/lib.sh"

worker="${1:?usage: corrupt-chunk.sh WORKER [CHUNK_ID]}"
chunk="${2:-$(api "/internal/workers/$worker/chunks" | jq -r '.chunks[0].chunk_id // empty')}"
[ -n "$chunk" ] || fail "$worker holds no chunks"

# The servable copy only: corrupting a quarantined file would prove nothing.
path="$(docker exec "$worker" find /data/chunks -name "$chunk.*" ! -name '*.corrupt' ! -name '*.tmp')"
[ -n "$path" ] || fail "$worker has no servable copy of $chunk"

docker exec "$worker" sh -c '
  byte=$(head -c 1 "$1" | od -An -tu1 | tr -d " ")
  printf "$(printf "\\%03o" $((byte ^ 1)))" | dd of="$1" bs=1 count=1 conv=notrunc 2>/dev/null
' sh "$path"
echo "Corrupted chunk $chunk on $worker ($path)"
