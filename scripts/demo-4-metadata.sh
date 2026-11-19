#!/usr/bin/env bash
# Demo 4: upload with custom metadata, read it, change it with PATCH, and show that the object's
# data (checksum, size, chunks) is untouched.

source "$(dirname "$0")/lib.sh"

step "Upload a 2.5 MB file with custom metadata"
file="$WORK_DIR/report.pdf"
head -c 2500000 /dev/urandom > "$file"
id="$(upload "$file" --form-string 'metadata={"project":"cc","version":1,"draft":true}' | jq -r .object_id)"
echo "  object_id $id"

step "Read it: GET /api/v1/objects/$id/metadata"
before="$(api "/api/v1/objects/$id/metadata")"
echo "$before" | jq '{name, size, content_type, checksum_sha256, chunk_count, created_at, updated_at, custom_metadata}'

step "Change it: PATCH /api/v1/objects/$id/metadata with {\"version\":2,\"reviewer\":\"rohit\"}"
sleep 1 # So the new updated_at is visibly later.
after="$(curl -fsS -X PATCH -H 'Content-Type: application/json' \
  -d '{"version":2,"reviewer":"rohit"}' "$CONTROLLER/api/v1/objects/$id/metadata")"
echo "$after" | jq '{updated_at, custom_metadata}'

step "Checks"
check "version updated, reviewer added, project and draft kept" \
  [ "$(echo "$after" | jq -c .custom_metadata)" = '{"project":"cc","version":2,"draft":true,"reviewer":"rohit"}' ]
check "values keep their JSON types (number, boolean)" \
  [ "$(echo "$after" | jq -r '[.custom_metadata.version, .custom_metadata.draft] | map(type) | join(",")')" = "number,boolean" ]
check "checksum unchanged" [ "$(echo "$before" | jq -r .checksum_sha256)" = "$(echo "$after" | jq -r .checksum_sha256)" ]
check "size unchanged" [ "$(echo "$before" | jq .size)" = "$(echo "$after" | jq .size)" ]
check "chunk list (IDs, sizes, checksums) unchanged" \
  [ "$(echo "$before" | jq -c '[.chunks[] | {chunk_id, size, checksum_sha256}]')" = "$(echo "$after" | jq -c '[.chunks[] | {chunk_id, size, checksum_sha256}]')" ]
check "created_at unchanged" [ "$(echo "$before" | jq -r .created_at)" = "$(echo "$after" | jq -r .created_at)" ]
check "updated_at moved forward" [ "$(echo "$after" | jq -r .updated_at)" \> "$(echo "$before" | jq -r .updated_at)" ]
downloaded="$WORK_DIR/downloaded.pdf"
curl -fsS -o "$downloaded" "$CONTROLLER/api/v1/objects/$id"
check "the bytes still download intact" [ "$(sha256 "$file")" = "$(sha256 "$downloaded")" ]

step "Invalid metadata is rejected: PATCH with a nested value"
code="$(curl -s -o "$WORK_DIR/err.json" -w '%{http_code}' -X PATCH -H 'Content-Type: application/json' \
  -d '{"nested":{"a":1}}' "$CONTROLLER/api/v1/objects/$id/metadata")"
jq -c . "$WORK_DIR/err.json" | sed 's/^/  /'
check "400 invalid_metadata" [ "$code $(jq -r .error "$WORK_DIR/err.json")" = "400 invalid_metadata" ]

echo; echo "Demo 4 passed."
