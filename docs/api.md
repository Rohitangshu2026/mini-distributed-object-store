# API reference

Exact payloads, headers and error codes for every endpoint. The endpoint tables in `CLAUDE.md` §13 are the summary; this file is the detail. Changing anything here is a contract change: ask Rohit first.

## 1. Conventions

- **JSON** is UTF-8 with snake_case field names. Internal request bodies ignore unknown fields, so formats can be extended.
- **Timestamps** are RFC 3339 UTC with millisecond precision, for example `2026-11-19T10:15:30.123Z`.
- **Object IDs** are UUID v7 in lowercase canonical form (36 characters). A path segment that is not a canonical lowercase UUID gets 400 `invalid_object_id`. A well-formed ID that does not exist gets 404.
- **Chunk IDs** are `{object_id}-{chunk_index}`, the index zero-padded to at least 6 digits (regex `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[0-9]{6,10}$`). Workers reject anything else, which also rules out path traversal.
- **Checksums** are SHA-256 as 64 lowercase hex characters.
- **Errors** always have this body (N8). The message is safe for clients: it never contains stack traces, SQL, file paths or raw library exception text.

  ```json
  { "error": "object_not_found", "message": "No object with ID 019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f." }
  ```

- **Any endpoint** may return 500 `internal_error` on an unexpected internal failure, even where its table does not list 500. The details go to the logs only.
- **"Success means"** under each endpoint defines what is complete when the success status is returned (N6).

## 2. Error codes

### Client API

| Code | Status | Returned by | Meaning and client action |
|---|---|---|---|
| `invalid_request` | 400 | any | Malformed request: wrong content type, unreadable body, bad query parameter, unexpected or duplicate multipart part. Fix the request. |
| `invalid_object_id` | 400 | object endpoints | The path segment is not a canonical lowercase UUID. |
| `missing_file` | 400 | POST objects | The multipart body has no `file` part. |
| `invalid_metadata` | 400 | POST objects, PATCH metadata | Custom metadata breaks a rule in §6. |
| `object_not_found` | 404 | GET object, GET/PATCH metadata, DELETE | No object with that ID. |
| `object_not_committed` | 409 | GET object, PATCH metadata, DELETE | The object is still uploading or is being deleted. Retry later; after a delete it becomes 404. |
| `insufficient_workers` | 503 | POST objects | Fewer than 2 ALIVE workers at the start, or no eligible worker left for a second copy during the upload. Nothing was stored. Retry when workers are back. |
| `chunk_unavailable` | 503 | GET object | Before the download started, some chunk had no HEALTHY copy on an ALIVE or SUSPECT worker. |
| `not_found` | 404 | any | No endpoint at this path. |
| `method_not_allowed` | 405 | any | The endpoint exists but not for this HTTP method. |
| `internal_error` | 500 | any | Unexpected failure; see the controller logs. |

### Internal API

| Code | Status | Returned by | Meaning |
|---|---|---|---|
| `invalid_request` | 400 | controller and worker | Malformed body, ID or query parameter. |
| `worker_not_found` | 404 | controller heartbeat and inventory; worker inventory | The worker is not registered with the controller, or the path's `worker_id` is not this worker. A worker that gets it on a heartbeat registers again. |
| `worker_unavailable` | 503 | controller live inventory | The worker could not be reached, or its inventory could not be read completely. |
| `invalid_chunk_id` | 400 | worker chunk endpoints | The chunk ID does not match the format in §1. |
| `missing_checksum` | 400 | worker PUT | `X-Checksum-SHA256` is absent or not 64 lowercase hex characters. |
| `invalid_chunk_size` | 400 | worker PUT | Empty body, or more than `CHUNK_SIZE` bytes. |
| `checksum_mismatch` | 400 on PUT, 409 on GET | worker PUT and GET | PUT: the body does not match the header; nothing was stored. GET: the stored bytes no longer match their recorded checksum; the file has been quarantined. |
| `chunk_not_found` | 404 | worker GET | No servable copy of that chunk. |
| `storage_unavailable` | 503 | worker PUT, GET, DELETE | A disk operation failed. For PUT and DELETE, nothing was changed or confirmed. |
| `internal_error` | 500 | any | Unexpected failure. |

How the controller classifies each worker response (permanent, transient, unknown outcome) is in `docs/architecture.md` §10.

## 3. Client API

### POST `/api/v1/objects`: upload

Request: `multipart/form-data` with:

| Part | Required | Content |
|---|---|---|
| `file` | yes, exactly one | The object's bytes. Its filename becomes the object name; without one, the object ID is used. Its `Content-Type` becomes the object's content type; without one, `MediaTypeFactory` guesses from the name, else `application/octet-stream`. |
| `metadata` | no, at most one | A JSON object of custom metadata (§6), any part content type. |

Any other part gets 400 `invalid_request`, so a misspelt `metdata` is never silently ignored. Parts may come in any order, because the whole request is staged before any chunk is written.

```bash
curl -sS --form-string 'metadata={"project":"cc","version":2}' -F 'file=@report.pdf' http://localhost:8080/api/v1/objects
```

Response `201 Created`, header `Location: /api/v1/objects/{object_id}`:

```json
{
  "object_id": "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f",
  "name": "report.pdf",
  "size": 20000000,
  "content_type": "application/pdf",
  "checksum_sha256": "4a1c…e9",
  "chunk_count": 20,
  "created_at": "2026-11-19T10:15:30.123Z",
  "updated_at": "2026-11-19T10:15:30.123Z",
  "custom_metadata": { "project": "cc", "version": 2 }
}
```

Success means: every chunk has 2 HEALTHY copies on 2 different workers, each confirmed and verified by its worker; the object row is `COMMITTED` with its size, checksum and chunk count; and that transaction is durable.

Errors: 400 `missing_file`, `invalid_metadata`, `invalid_request`; 503 `insufficient_workers`; 500. After any error the object is not listed, and every copy already written is deleted or covered by a tombstone. Uploads are not idempotent: retrying after a lost 201 creates a second object.

### GET `/api/v1/objects/{object_id}`: download

Response `200 OK`:

| Header | Value |
|---|---|
| `Content-Type` | The stored content type |
| `Content-Length` | The object size |
| `X-Checksum-SHA256` | The whole-object checksum |

Body: the object's bytes. A 0-byte object returns an empty body.

Success means: every byte sent passed its chunk's checksum check at the controller, and the whole-object checksum matched before the last chunk was sent.

Errors before any byte is sent: 400 `invalid_object_id`; 404 `object_not_found`; 409 `object_not_committed`; 503 `chunk_unavailable`; 500.

A failure after the headers were sent (every copy of a later chunk fails, or the whole-object checksum does not match) aborts the response, so the client receives fewer bytes than `Content-Length`. `curl` reports this as exit code 18; `objstore get` reports it and also checks `X-Checksum-SHA256`.

### GET `/api/v1/objects/{object_id}/metadata`

Response `200 OK`:

```json
{
  "object_id": "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f",
  "name": "report.pdf",
  "size": 20000000,
  "content_type": "application/pdf",
  "checksum_sha256": "4a1c…e9",
  "chunk_count": 20,
  "status": "COMMITTED",
  "created_at": "2026-11-19T10:15:30.123Z",
  "updated_at": "2026-11-19T10:16:02.511Z",
  "custom_metadata": { "project": "cc", "version": 2 },
  "chunks": [
    {
      "chunk_id": "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f-000000",
      "index": 0,
      "size": 1000000,
      "checksum_sha256": "77af…01",
      "replicas": [
        { "worker_id": "worker1", "status": "HEALTHY", "last_verified_at": "2026-11-19T10:15:30.201Z" },
        { "worker_id": "worker3", "status": "HEALTHY", "last_verified_at": "2026-11-19T10:15:30.204Z" }
      ]
    }
  ]
}
```

Objects in any status are returned. While `UPLOADING`, `size`, `checksum_sha256` and `chunk_count` are `null` and `chunks` lists those recorded so far. While `DELETING`, chunks are listed until they are removed, with no replicas (their copies are tombstones).

Errors: 400 `invalid_object_id`; 404 `object_not_found`.

### PATCH `/api/v1/objects/{object_id}/metadata`

Request: `Content-Type: application/json`, a flat JSON object of keys to add or update (§6). Keys cannot be removed; `{}` is accepted and only bumps `updated_at`.

```bash
curl -sS -X PATCH -H 'Content-Type: application/json' -d '{"version":3,"reviewed":true}' http://localhost:8080/api/v1/objects/019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f/metadata
```

Response `200 OK`: the same body as GET metadata.

Success means: the merged custom metadata and the new `updated_at` are committed in one transaction. Chunks, size and checksum are untouched.

Errors: 400 `invalid_object_id`, `invalid_metadata`, `invalid_request`; 404 `object_not_found`; 409 `object_not_committed`.

### DELETE `/api/v1/objects/{object_id}`

Response `204 No Content`.

Success means: the object's metadata is gone (GET returns 404), and every copy of its chunks is either deleted or covered by a durable tombstone, which is processed when its worker is ALIVE.

Errors: 400 `invalid_object_id`; 404 `object_not_found`; 409 `object_not_committed` (still uploading, or a delete is already in progress); 500. Never 503.

### GET `/api/v1/objects?limit=&offset=`: list

| Parameter | Rule | Default |
|---|---|---|
| `limit` | Integer 1–1000 | 50 |
| `offset` | Integer ≥ 0 | 0 |

Anything else gets 400 `invalid_request`.

Response `200 OK`, only `COMMITTED` objects, ordered by `object_id` (creation order):

```json
{
  "objects": [
    {
      "object_id": "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f",
      "name": "report.pdf",
      "size": 20000000,
      "content_type": "application/pdf",
      "created_at": "2026-11-19T10:15:30.123Z"
    }
  ],
  "limit": 50,
  "offset": 0,
  "total": 1
}
```

## 4. Internal API: controller side

### POST `/internal/workers/register`

Request:

```json
{ "worker_id": "worker2", "url": "http://worker2:9000", "boot_count": 7 }
```

`worker_id` matches `^[a-z0-9-]{1,32}$`; `url` is an absolute `http` or `https` URL. Otherwise 400 `invalid_request`.

Response `200 OK`:

```json
{ "worker_id": "worker2", "registered_at": "2026-11-19T09:00:00.000Z" }
```

Idempotent. A new URL replaces the old one. Every registration records a heartbeat and schedules reconciliation of that worker; the worker becomes ALIVE at the next membership check (within 1 s).

### POST `/internal/workers/heartbeat`

Request:

```json
{ "worker_id": "worker2", "chunk_count": 120, "used_bytes": 119000000, "corrupt_chunks": ["019a8f2e-…-000003"] }
```

`corrupt_chunks` lists chunks the worker quarantined since its last heartbeat that got a 2xx; the worker clears the list only after a 2xx. The controller marks each listed replica CORRUPT (if it was HEALTHY) and triggers repair.

Response `204 No Content`. 404 `worker_not_found` if the worker is not registered; the worker then registers again.

### GET `/internal/workers`

Response `200 OK`, ordered by `worker_id`:

```json
{
  "workers": [
    {
      "worker_id": "worker2",
      "url": "http://worker2:9000",
      "state": "ALIVE",
      "last_heartbeat_at": "2026-11-19T10:15:29.002Z",
      "last_heartbeat_age_ms": 1210,
      "reported_chunk_count": 120,
      "used_bytes": 119000000,
      "healthy_replicas": 120
    }
  ]
}
```

`healthy_replicas` comes from the metadata; `reported_chunk_count` and `used_bytes` come from the worker's last heartbeat (`null` before the first one).

### GET `/internal/workers/{worker_id}/chunks`

Without a query: the worker's live inventory, fetched from the worker and returned unchanged (worker §5 body). 404 `worker_not_found` if the worker is not registered; 503 `worker_unavailable` if it is unreachable or its inventory cannot be read completely.

With `?view=expected`: what the metadata says the worker should hold:

```json
{
  "worker_id": "worker2",
  "view": "expected",
  "chunks": [ { "chunk_id": "019a8f2e-…-000000", "size": 1000000, "checksum_sha256": "77af…01", "status": "HEALTHY" } ],
  "tombstones": [ "019a77c0-…-000004" ]
}
```

Any other `view` value gets 400 `invalid_request`.

### GET `/internal/replication`

Response `200 OK`:

```json
{
  "replication_factor": 2,
  "total_chunks": 120,
  "healthy": 118,
  "under_replicated": 2,
  "unavailable": 0,
  "pending_tombstones": 3,
  "chunks": [
    {
      "chunk_id": "019a8f2e-…-000007",
      "object_id": "019a8f2e-7c1d-7b3a-9f1e-2a3b4c5d6e7f",
      "healthy_copies": 1,
      "replicas": [
        { "worker_id": "worker1", "status": "HEALTHY", "worker_state": "ALIVE" },
        { "worker_id": "worker2", "status": "LOST", "worker_state": "DEAD" }
      ]
    }
  ],
  "truncated": false
}
```

Definitions:

- Only chunks of `COMMITTED` objects are counted.
- `healthy_copies` counts HEALTHY replicas on ALIVE workers. A chunk is *healthy* with 2 or more, *under-replicated* with 1, and *unavailable* with 0.
- A copy on a SUSPECT worker is not counted, so the report shows under-replicated chunks during the SUSPECT window even though downloads still use those copies.
- `chunks` lists only chunks that are not healthy, at most 1000; `truncated` is `true` when more exist.
- The cluster is *fully replicated* when `under_replicated` and `unavailable` are both 0.

### GET `/health` (controller)

`200 OK` with `{"status":"UP","service":"controller","version":"…"}` when the application is running and SQLite answers `SELECT 1`; otherwise `503` with `{"status":"DOWN","service":"controller","version":"…"}`.

## 5. Internal API: worker side

### PUT `/internal/chunks/{chunk_id}`

Headers: `X-Checksum-SHA256` (required). The body is raw bytes, 1 to `CHUNK_SIZE` of them; its content type is not checked.

Response `200 OK`:

```json
{ "chunk_id": "019a8f2e-…-000000", "size": 1000000, "checksum_sha256": "77af…01" }
```

Success means: the bytes were hashed as they arrived, matched the header, were written to a temp file, forced to disk, atomically renamed to `<chunk_id>.<checksum>`, and the directory was forced. Repeating the same PUT is safe. A successful PUT replaces any other servable file for that chunk ID and removes its quarantined files.

Errors: 400 `invalid_chunk_id`, `missing_checksum`, `invalid_chunk_size`, `checksum_mismatch` (nothing stored); 503 `storage_unavailable` (nothing stored, temp file removed); 500.

### GET `/internal/chunks/{chunk_id}`

Response `200 OK`, `Content-Type: application/octet-stream`, `Content-Length`, `X-Checksum-SHA256` (the recorded checksum, which the bytes have just matched). Body: the chunk bytes.

Success means: the whole file was read and re-hashed, and matched its recorded checksum, before the response started.

Errors: 400 `invalid_chunk_id`; 404 `chunk_not_found`; 409 `checksum_mismatch` (the file has been renamed to `.corrupt` and is reported in the next heartbeat); 503 `storage_unavailable`; 500.

### DELETE `/internal/chunks/{chunk_id}`

Response `204 No Content`, whether or not the chunk existed. It removes the servable file and any quarantined files for that chunk ID, then forces the directory.

Errors: 400 `invalid_chunk_id`; 503 `storage_unavailable` (nothing confirmed, so the controller keeps its tombstone).

### GET `/internal/workers/{worker_id}/chunks`

Response `200 OK`:

```json
{
  "worker_id": "worker2",
  "chunks": [ { "chunk_id": "019a8f2e-…-000000", "size": 1000000, "checksum_sha256": "77af…01" } ],
  "quarantined": [ "019a8f2e-…-000003" ]
}
```

The inventory is complete or it is an error, never partial: it is built in one pass from the worker's in-memory index. `checksum_sha256` is the recorded value from the file name and has not been re-verified.

Errors: 404 `worker_not_found` if `worker_id` is not this worker; 500.

### GET `/health` (worker)

`200 OK`:

```json
{ "status": "UP", "worker_id": "worker2", "version": "…", "boot_count": 7, "chunk_count": 120 }
```

## 6. Custom metadata rules

The same rules apply to the upload `metadata` part and to the PATCH body. For PATCH they are checked on the merged result.

- The top level is a JSON object.
- Keys are 1 to 128 characters (Unicode code points) and case-sensitive. A key that appears twice in one document is rejected.
- Values are strings, numbers or booleans. `null`, arrays and objects are rejected.
- The compact JSON serialisation of the whole map is at most 16,384 bytes of UTF-8.
- Values are stored and returned with their JSON types; numbers keep their exact value.

Breaking any rule gets 400 `invalid_metadata`, with a message that names the rule and the key.
