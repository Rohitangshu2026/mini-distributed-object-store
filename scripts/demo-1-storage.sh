#!/usr/bin/env bash
# Demo 1: store an arbitrary binary object and get back exactly the same bytes.

source "$(dirname "$0")/lib.sh"

step "Create a 3.5 MB file of random bytes"
original="$WORK_DIR/photo.jpg"
head -c 3500000 /dev/urandom > "$original"
echo "  $(wc -c < "$original" | tr -d ' ') bytes, sha256 $(sha256 "$original")"

step "Upload it: POST /api/v1/objects"
response="$(upload "$original")"
echo "$response" | jq .
id="$(echo "$response" | jq -r .object_id)"

step "Download it: GET /api/v1/objects/$id"
downloaded="$WORK_DIR/downloaded.jpg"
curl -fsS -D "$WORK_DIR/headers" -o "$downloaded" "$CONTROLLER/api/v1/objects/$id"
grep -i -E '^(content-type|content-length|x-checksum-sha256):' "$WORK_DIR/headers" | sed 's/^/  /'

step "Compare"
original_sha="$(sha256 "$original")"
downloaded_sha="$(sha256 "$downloaded")"
echo "  original   $original_sha"
echo "  downloaded $downloaded_sha"
check "downloaded bytes have the original's SHA-256" [ "$original_sha" = "$downloaded_sha" ]
check "controller's recorded checksum matches too" [ "$(echo "$response" | jq -r .checksum_sha256)" = "$original_sha" ]
check "content type was guessed from the name" [ "$(echo "$response" | jq -r .content_type)" = "image/jpeg" ]

echo; echo "Demo 1 passed."
