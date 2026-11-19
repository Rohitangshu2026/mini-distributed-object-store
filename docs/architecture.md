# Architecture

How the system is built: components, code layout, concurrency, data model, state machines, flows, and failure handling. The rules are in `CLAUDE.md`, the payloads are in `docs/api.md`, and the reasons are in `docs/decisions/`. Read the section for the code you are about to change.

Contents:

1. Overview
2. Repository layout
3. Modules and packages
4. Seams and test doubles
5. Concurrency model
6. Configuration
7. Data model
8. State machines
9. Flows
10. Failure handling
11. SQLite and transactions
12. Worker storage
13. Observability and security

## 1. Overview

```
client (objstore CLI / curl) ──REST──▶ controller :8080 ──internal HTTP──▶ worker1..worker4 :9000
        host or `client` container        │                                   │
                                          ├─ SQLite on controller-data        └─ chunk files on workerN-data
                                          └─ upload staging in /tmp/objstore-uploads
```

- **Controller.** Owns the client API, the metadata index (SQLite, authoritative), membership, placement, repair, reconciliation, tombstones, orphan cleanup and recovery. It is the only component that knows what an object is.
- **Workers.** Each stores chunk files on its own volume, verifies every chunk it writes and reads, reports liveness and corruption through heartbeats, and scrubs its disk in the background. A worker knows chunk IDs and bytes, nothing else.
- **Clients.** The `objstore` CLI (in the `client` container or on the host) and plain `curl`. They reach objects only through `/api/v1` on the controller.

The central idea for correctness: the database always records an intent before a worker is asked to change anything, every worker operation is safe to repeat, and recovery replays recorded intents (N4, §8, §10).

## 2. Repository layout

```
mini-distributed-object-store/
├── pom.xml                     root POM; parent spring-boot-starter-parent 4.1.2; modules, plugins, e2e profile
├── mvnw  mvnw.cmd  .mvn/
│   ├── wrapper/maven-wrapper.properties    pins Maven 3.9.11
│   └── jvm.config              --add-exports flags required by Error Prone
├── Dockerfile                  build stage + targets controller, worker, client
├── docker-compose.yml          controller, worker1..worker4, client (profile "client")
├── Makefile                    up, down, logs, ps, test, e2e, demo, reset
├── README.md
├── .github/workflows/ci.yml
├── .gitignore  .dockerignore
├── common/
│   ├── pom.xml                 also builds a test-jar (MutableClock)
│   └── src/
│       ├── main/java/io/github/rohitangshu2026/objstore/common/
│       │   ├── Chunking.java          CHUNK_SIZE, chunk count, chunk boundaries
│       │   ├── ChunkId.java           format, parse, validate
│       │   ├── Checksums.java         SHA-256 of bytes and streams, hex
│       │   ├── UuidV7.java            object ID generator (Clock + RandomGenerator)
│       │   ├── Timestamps.java        epoch millis ⇄ RFC 3339
│       │   ├── api/                   request and response records (snake_case via @JsonNaming)
│       │   └── error/                 ErrorCode, ApiException, ErrorBody
│       └── test/java/…/common/        *Test, MutableClock
├── controller/
│   └── src/
│       ├── main/java/io/github/rohitangshu2026/objstore/controller/
│       │   ├── ControllerApplication.java
│       │   ├── config/        ControllerProperties, ControllerConfig (Clock, RandomGenerator, executors,
│       │   │                  schedulers, RestClient, TransactionTemplate)
│       │   ├── api/           ObjectApi, InternalApi, HealthApi, ApiExceptionHandler
│       │   ├── object/        UploadService, DownloadService, ObjectService, ObjectRemoval,
│       │   │                  CustomMetadata, InFlightObjects
│       │   ├── metadata/      ObjectRepository, ReplicaRepository, WorkerRepository, TombstoneRepository,
│       │   │                  row records, status enums
│       │   ├── membership/    Membership, WorkerStateChanged
│       │   ├── placement/     Placement
│       │   ├── repair/        RepairJob
│       │   ├── reconcile/     Reconciler, TombstoneJob, OrphanTracker, RecoveryJob
│       │   └── workerclient/  WorkerClient, HttpWorkerClient, WorkerRef, WorkerCallException
│       ├── main/resources/    application.yml, db/migration/V1__init.sql
│       └── test/java/…/controller/   *Test, *IT, FakeWorkerClient
├── worker/
│   └── src/
│       ├── main/java/io/github/rohitangshu2026/objstore/worker/
│       │   ├── WorkerApplication.java
│       │   ├── config/        WorkerProperties, WorkerConfig (Clock, schedulers, RestClient)
│       │   ├── api/           ChunkApi, HealthApi, ApiExceptionHandler
│       │   ├── store/         ChunkStore, BootCounter
│       │   ├── heartbeat/     ControllerLink (registration and heartbeats)
│       │   └── scrub/         Scrubber
│       ├── main/resources/    application.yml
│       └── test/java/…/worker/       *Test, *IT
├── cli/
│   └── src/main/java/io/github/rohitangshu2026/objstore/cli/
│       ├── ObjstoreCli.java   picocli command; subcommands as methods: put, get, ls, stat, meta, rm, status, verify
│       └── ControllerHttp.java JDK HttpClient calls, JSON, error decoding, streaming multipart body
├── e2e/
│   └── src/test/java/io/github/rohitangshu2026/objstore/e2e/
│       ├── StorageIT  ChunkingIT  ReplicationIT  MetadataIT  FailureIT  IntegrityIT  ApiContractIT
│       └── Cluster.java       base URL, docker commands, polling helpers
├── scripts/                   lib.sh, cluster-status.sh, corrupt-chunk.sh, demo-1-storage.sh … demo-6-integrity.sh
└── docs/                      brief.pdf, api.md, architecture.md, roadmap.md, benchmarks.md (Phase 10), decisions/
```

Class names are the plan. A phase may merge or split a class with a stated reason (P8), but never adds an interface without a second implementation (P2).

## 3. Modules and packages

| Module | Depends on | Must not depend on | Notes |
|---|---|---|---|
| `objstore-common` | Jackson databind (annotations and naming) | Spring | Shared by every module, including the plain-Java CLI |
| `objstore-controller` | common, Boot starters `webmvc`, `jdbc`, `flyway`, `restclient`, sqlite-jdbc | worker, cli | |
| `objstore-worker` | common, Boot starters `webmvc`, `restclient` | controller, cli, any database | |
| `objstore-cli` | common, picocli | Spring | Packaged as a runnable jar by `spring-boot-maven-plugin` (plain `main`, no Spring context) |
| `objstore-e2e` | common, JUnit, AssertJ | controller, worker internals | Only in the `e2e` Maven profile |

Packages are by feature (`object`, `membership`, `repair`…), not by layer. Each Spring application has one `api` package holding its controllers and its single `ApiExceptionHandler`.

## 4. Seams and test doubles

| Seam | Production | Tests | Why it exists |
|---|---|---|---|
| `WorkerClient` (interface) | `HttpWorkerClient` (`RestClient` on JDK `HttpClient`, HTTP/1.1, connect 1 s, request 5 s) | `FakeWorkerClient`: in-memory workers with switches for down, slow, corrupt chunk, write applied but response lost, failing or partial inventory; counts concurrent calls | Every distributed failure can be injected deterministically |
| `java.time.Clock` | `Clock.systemUTC()` | `MutableClock` (common test-jar) with `advance(Duration)` | Heartbeat ages, grace periods and timestamps are testable without sleeping |
| `java.util.random.RandomGenerator` | `SecureRandom` | Seeded `Random` | UUID v7 random bits |
| `ChunkStore` (concrete class) | `java.nio.file` I/O | The real class on JUnit `@TempDir`; tests corrupt files on disk directly | A fake would hide the bugs that matter (rename, fsync, partial files) |

`WorkerClient` methods take a `WorkerRef(workerId, url)`:

- `putChunk(ref, chunkId, bytes, length, checksum)`;
- `getChunk(ref, chunkId)`, which returns the bytes;
- `deleteChunk(ref, chunkId)`;
- `inventory(ref)`.

Every failure is a `WorkerCallException` carrying a `Failure` kind (§10.1), the worker ID, the operation, the chunk ID and the cause.

**Background jobs.** Each job is a class with:

- `runOnce()`, which does one pass, catches and logs per-item failures, and returns. Tests call it directly.
- A `@Scheduled(fixedDelayString = …, scheduler = "<its scheduler>")` method that calls `runOnce()`.
- `trigger()`, which schedules one immediate extra run on the same scheduler. An `AtomicBoolean` collapses repeated triggers into one run.

**Events.** Spring `ApplicationEventPublisher` decouples producers from the jobs that react, so no package depends on a later phase's code. Listeners only call `trigger()`.

| Event | Published by | Listener |
|---|---|---|
| `WorkerStateChanged` | `Membership` | `RepairJob` on →DEAD; `Reconciler` on DEAD→ALIVE |
| `WorkerRegistered` | `Membership` | `Reconciler` |
| `ReplicaDegraded` | download, reconciliation, heartbeat corrupt reports | `RepairJob` |

## 5. Concurrency model

Platform threads only (decision 0002). Every pool below is a Spring bean with a fixed size. Each pool finishes its tasks on shutdown, waiting at most 5 s.

| Bean | Kind | Size | Runs |
|---|---|---|---|
| Tomcat request threads | Tomcat | 200 (default) | All HTTP requests on controller and workers |
| `replicaWriteExecutor` | `ThreadPoolTaskExecutor` | 8 threads, queue 256 | The second copy of each chunk during upload; the request thread writes the first copy itself |
| `repairExecutor` | `ThreadPoolTaskExecutor` | `REPAIR_CONCURRENCY` threads, queue 100 | Chunk repairs submitted by one `RepairJob.runOnce()` (batches of at most 100) |
| `membershipScheduler` | `ThreadPoolTaskScheduler` | 1 | `Membership.runOnce()` every 1 s |
| `repairScheduler` | `ThreadPoolTaskScheduler` | 1 | `RepairJob.runOnce()` every 30 s and on trigger |
| `maintenanceScheduler` | `ThreadPoolTaskScheduler` | 1 | `RecoveryJob` (startup, then every 10 s), `TombstoneJob` (every 10 s and on trigger), `Reconciler` (on trigger, and every 300 s per ALIVE worker) |
| `heartbeatScheduler` (worker) | `ThreadPoolTaskScheduler` | 1 | `ControllerLink`: register until accepted, then heartbeat every `HEARTBEAT_INTERVAL_MS` |
| `scrubScheduler` (worker) | `ThreadPoolTaskScheduler` | 1 | `Scrubber.runOnce()` every `SCRUB_INTERVAL_SECS` |

Queue sizes are larger than the number of producers that can wait on them, so a rejected task means a bug; it is logged at ERROR and the operation fails cleanly.

Shared mutable state and how it is protected:

| State | Owner | Protection |
|---|---|---|
| Worker last-seen times and reported stats | Written by heartbeat threads | `ConcurrentHashMap` of immutable records |
| Worker states (ALIVE/SUSPECT/DEAD) | Only the `membershipScheduler` thread changes them | Thread confinement; others read snapshots |
| In-flight object IDs (uploads, deletes) | `InFlightObjects` | `ConcurrentHashMap.newKeySet()` |
| Worker chunk index | `ChunkStore` | `ConcurrentHashMap`, plus 64 striped `ReentrantLock`s by chunk ID around write, delete and verify-then-quarantine |
| Corrupt chunks not yet reported | `ChunkStore` | `ConcurrentHashMap.newKeySet()` |
| Orphan sightings | `OrphanTracker` | Confined to the `maintenanceScheduler` thread |
| Job trigger flags | Each job | `AtomicBoolean` |
| Metadata | SQLite | Transactions (§11) |

No `synchronized` block or lock is held across network I/O. No SQLite transaction spans network or file I/O.

## 6. Configuration

Each Spring application binds one `@ConfigurationProperties` record (`ControllerProperties`, `WorkerProperties`) whose compact constructor validates values (for example, positive intervals, and `DEAD_AFTER_MISSED` greater than `SUSPECT_AFTER_MISSED`). `application.yml` maps the env names from `CLAUDE.md` §11 explicitly, so the env table stays the single list of knobs.

| Env | Property | Type |
|---|---|---|
| `SERVER_PORT` | `server.port` | int |
| `DATABASE_URL` | `spring.datasource.url` | JDBC URL |
| `HEARTBEAT_INTERVAL_MS` | `objstore.heartbeat-interval` | Duration |
| `SUSPECT_AFTER_MISSED`, `DEAD_AFTER_MISSED` | `objstore.suspect-after-missed`, `objstore.dead-after-missed` | int |
| `REPAIR_CONCURRENCY` | `objstore.repair-concurrency` | int |
| `GC_GRACE_SECS`, `STARTUP_GRACE_SECS` | `objstore.gc-grace`, `objstore.startup-grace` | Duration |
| `GC_DRY_RUN` | `objstore.gc-dry-run` | boolean |
| `WORKER_ID`, `WORKER_ADVERTISE_URL`, `CONTROLLER_URL`, `DATA_DIR`, `SCRUB_INTERVAL_SECS` | `objstore.worker-id`, `objstore.advertise-url`, `objstore.controller-url`, `objstore.data-dir`, `objstore.scrub-interval` | string / URI / Path / Duration |

Fixed settings, deliberately not configurable:

- `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=5s`.
- Multipart: `spring.servlet.multipart.resolve-lazily=true`, `max-file-size=-1`, `max-request-size=-1`, `file-size-threshold=0`, `location=/tmp/objstore-uploads`.
- `logging.structured.format.console=ecs`.
- Worker: `spring.mvc.formcontent.filter.enabled=false`. curl sends a `PUT` body as form data by default, and Spring's form filter would otherwise consume it before `ChunkApi` reads it.
- Worker: `logging.structured.json.add.worker_id`, so every worker log line carries its `worker_id`.
- Hikari `maximum-pool-size=4` and `connection-timeout=5000` (a request waits for a free connection no longer than for the write lock), plus the SQLite properties in §11.

## 7. Data model

Phase 2 copies this DDL into `controller/src/main/resources/db/migration/V1__init.sql`. From then on the migration is authoritative and this section only explains it.

```sql
CREATE TABLE workers (
  worker_id         TEXT    PRIMARY KEY,
  url               TEXT    NOT NULL,
  state             TEXT    NOT NULL CHECK (state IN ('ALIVE', 'SUSPECT', 'DEAD')),
  last_heartbeat_at INTEGER NOT NULL,              -- epoch ms, updated on registration and state changes
  registered_at     INTEGER NOT NULL
) STRICT;

CREATE TABLE objects (
  object_id        TEXT    PRIMARY KEY,            -- UUID v7, lowercase canonical
  name             TEXT    NOT NULL,
  size             INTEGER,                        -- NULL until COMMITTED
  content_type     TEXT    NOT NULL,
  checksum_sha256  TEXT,                           -- NULL until COMMITTED
  chunk_count      INTEGER,                        -- NULL until COMMITTED
  status           TEXT    NOT NULL CHECK (status IN ('UPLOADING', 'COMMITTED', 'DELETING')),
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL,
  custom_metadata  TEXT    NOT NULL DEFAULT '{}',  -- JSON object
  CHECK (status <> 'COMMITTED' OR (size IS NOT NULL AND checksum_sha256 IS NOT NULL AND chunk_count IS NOT NULL))
) STRICT;
CREATE INDEX objects_by_status ON objects (status, object_id);

CREATE TABLE chunks (
  chunk_id        TEXT    PRIMARY KEY,
  object_id       TEXT    NOT NULL REFERENCES objects (object_id) ON DELETE CASCADE,
  chunk_index     INTEGER NOT NULL CHECK (chunk_index >= 0),
  size            INTEGER NOT NULL CHECK (size > 0),
  checksum_sha256 TEXT    NOT NULL,
  UNIQUE (object_id, chunk_index)
) STRICT;

CREATE TABLE chunk_replicas (
  chunk_id         TEXT    NOT NULL REFERENCES chunks (chunk_id) ON DELETE CASCADE,
  worker_id        TEXT    NOT NULL REFERENCES workers (worker_id),
  status           TEXT    NOT NULL CHECK (status IN ('PENDING', 'HEALTHY', 'LOST', 'CORRUPT')),
  last_verified_at INTEGER,
  PRIMARY KEY (chunk_id, worker_id)                -- I4: at most one copy per worker
) STRICT;
CREATE INDEX chunk_replicas_by_worker ON chunk_replicas (worker_id, status);

CREATE TABLE tombstones (
  chunk_id   TEXT    NOT NULL,                     -- no FK: a tombstone outlives its chunk row
  worker_id  TEXT    NOT NULL REFERENCES workers (worker_id),
  reason     TEXT    NOT NULL CHECK (reason IN ('OBJECT_DELETED', 'UPLOAD_ABORTED', 'WRITE_FAILED',
                                                  'REPAIR_INTERRUPTED', 'EXTRA_COPY', 'UNTRACKED_COPY', 'ORPHAN')),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (chunk_id, worker_id)
) STRICT;
CREATE INDEX tombstones_by_worker ON tombstones (worker_id);
```

Notes:

- `ON DELETE CASCADE` from `chunks` to `chunk_replicas` is safe only because every flow turns replica rows into tombstones in an earlier transaction (D1). A test asserts that no object row is removed while it still has replica rows.
- The object ID is the prefix of every chunk ID, so "which object owns this chunk file" is a lookup by ID, even after the chunk row is gone.
- Status enums in Java: `ObjectStatus`, `ReplicaStatus`, `WorkerState`, `TombstoneReason`, stored by name.

## 8. State machines

Changing a transition in this section needs Rohit's approval.

### Object

```
(none) ──U2──▶ UPLOADING ──U7──▶ COMMITTED ──D1──▶ DELETING ──D3──▶ (row removed)
                   │                                    ▲
                   └────── abort, or found abandoned ───┘
```

| From | To | When |
|---|---|---|
| — | UPLOADING | Upload request validated (U2) |
| UPLOADING | COMMITTED | Every chunk has 2 HEALTHY copies (U7) |
| UPLOADING | DELETING | Upload aborted, or recovery finds it not in flight |
| COMMITTED | DELETING | DELETE accepted (D1) |
| DELETING | removed | All replica rows already tombstones (D3) |

### Replica

| From | To | When |
|---|---|---|
| — | PENDING | Intent recorded before a PUT (upload or repair) |
| PENDING | HEALTHY | The worker confirmed a verified write (N2) |
| PENDING | tombstone | Write failed or outcome unknown; upload aborted; restart found a repair interrupted |
| HEALTHY | LOST | Worker went DEAD; or a complete inventory lacks the file |
| HEALTHY | CORRUPT | A read failed verification at the worker or the controller; the worker reported it; or the recorded checksum differs from the metadata |
| LOST | HEALTHY | Rejoin found the copy present, verified and still needed; or it was rewritten in place on an ALIVE worker |
| LOST | tombstone | Rejoin found the copy present, but 2 HEALTHY copies exist elsewhere |
| LOST | removed | Reconciliation found no file on an ALIVE worker, and 2 HEALTHY copies exist elsewhere (there is nothing on disk to delete) |
| CORRUPT | HEALTHY | Rewritten in place with a verified copy |
| CORRUPT | tombstone | Reconciliation found 2 HEALTHY copies elsewhere; the tombstone removes the quarantined file |
| any | tombstone | Object deleted (D1) or upload aborted |

"→ tombstone" is one transaction that deletes the replica row and inserts the tombstone.

### Worker

| From | To | When |
|---|---|---|
| — | SUSPECT | First registration inserts the row; controller startup sets every worker SUSPECT |
| SUSPECT | ALIVE | The membership check sees a heartbeat younger than `SUSPECT_AFTER_MISSED × interval` |
| ALIVE | SUSPECT | Heartbeat age ≥ `SUSPECT_AFTER_MISSED × interval` (9 s) |
| ALIVE, SUSPECT | DEAD | Heartbeat age ≥ `DEAD_AFTER_MISSED × interval` (15 s). Its HEALTHY replicas become LOST in the same transaction, then `WorkerStateChanged` triggers repair |
| DEAD | ALIVE | Fresh heartbeat or registration. `WorkerStateChanged` triggers reconciliation |

Only the membership check changes states. Every registration also publishes `WorkerRegistered`, because a restarted worker's disk may have changed even if it was never declared DEAD.

### Tombstone

A tombstone is the only route to deleting a copy (N5). `TombstoneJob` processes a tombstone only when its worker is ALIVE. A 404 from the worker counts as confirmed.

| The tombstone's object is | Action |
|---|---|
| DELETING, or absent (deleted or never known) | Send DELETE; remove the tombstone once the worker confirms |
| UPLOADING | Wait until the upload commits or is aborted |
| COMMITTED, and ≥ 2 HEALTHY copies on ALIVE workers will remain | Send DELETE; remove the tombstone once the worker confirms |
| COMMITTED, and fewer would remain | Delete nothing. Read and verify the copy (GET, then the controller checks it against the metadata) |

When fewer than 2 healthy copies would remain, the outcome of that read decides what happens:

| Read result | What happens |
|---|---|
| Valid | Adopt it: the tombstone becomes a HEALTHY replica in one transaction |
| `checksum_mismatch` | The worker has quarantined it; remove the tombstone |
| `chunk_not_found` | Remove the tombstone |
| No answer | Keep the tombstone for the next run |

Placement never chooses a worker that holds a replica row or a tombstone for that chunk, so a pending delete can never race a fresh copy.

## 9. Flows

`[tx]` marks a transaction; no transaction spans a network call.

### Upload: `UploadService`

1. **U1.** `ObjectApi` takes the lazily resolved `MultipartHttpServletRequest` and checks that at least 2 workers are ALIVE before touching any part. Otherwise: 503 `insufficient_workers` (the body is never staged).
2. Read the parts. Tomcat stages the whole body to `/tmp/objstore-uploads`. Then check:
   - exactly one `file` part and at most one `metadata` part, with no other parts;
   - the metadata parses and passes `CustomMetadata` validation;
   - the name and content type are resolved.

   Any failure gets a 400, and nothing has been stored.
3. **U2.** Generate the object ID and add it to `InFlightObjects` first, so recovery can never see this upload as abandoned. Then `[tx]` insert the object as `UPLOADING`.
4. Read the `file` part's stream into one reused `byte[CHUNK_SIZE]` with `readNBytes`. For each chunk *i*, run U3–U6:
   1. **U3.** Hash the chunk, form `chunk_id`, and feed the bytes to the whole-object digest.
   2. **U4.** Placement picks 2 eligible workers from a load snapshot taken at upload start. `[tx]` Insert the chunk row and 2 PENDING rows.
   3. **U5.** Write the first copy on the request thread and the second on `replicaWriteExecutor`, then wait for both. The buffer is reused only after both writes finish.
   4. **U6.** For each confirmed copy: `[tx]` PENDING → HEALTHY with `last_verified_at`. For each failed copy, whether it failed or its outcome is unknown: `[tx]` PENDING → tombstone `WRITE_FAILED` and a new PENDING row on the next eligible worker, then write to that worker. Repeat while eligible workers remain; with none left, the upload is aborted with 503.
5. **U7.** `[tx]` Set size, checksum, chunk count, `COMMITTED` and `updated_at`. Remove the ID from `InFlightObjects` and return 201.

**Abort.** On any exception after U2, including client disconnect:

1. `ObjectRemoval.begin(objectId, UPLOADING)`: `[tx]` UPLOADING → DELETING, every replica row → tombstone `UPLOAD_ABORTED`.
2. `attemptDeletes`, then `finish` (as D2 and D3).
3. Remove the ID from `InFlightObjects` and return the original error.

If the abort itself fails, it is logged and recovery finishes it. Spring deletes the staged temp file when the request ends; startup wipes the staging directory before the web server starts.

### Download: `DownloadService`

1. `[read]` One query loads the object, its chunks, their replicas and the worker states. Unknown → 404; not COMMITTED → 409.
2. Pre-check: every chunk has at least one HEALTHY replica on an ALIVE or SUSPECT worker. Otherwise: 503 `chunk_unavailable`.
3. Set `Content-Type`, `Content-Length` and `X-Checksum-SHA256`, then write to the servlet output stream directly from the request thread (no async, so no async timeout).
4. For each chunk in index order, try its copies, ALIVE workers before SUSPECT ones:
   1. `getChunk` returns the bytes; check the size and SHA-256 against the chunk row.
   2. On a mismatch, or a worker 409: `[tx]` HEALTHY → CORRUPT, publish `ReplicaDegraded`, try the next copy.
   3. On a worker 404: `[tx]` HEALTHY → LOST, publish `ReplicaDegraded`, try the next copy.
   4. On a transient or unknown failure: try the next copy and change nothing (membership owns liveness).
   5. When every copy has failed: log ERROR, abort the response (truncated body).
5. Feed each verified chunk to the whole-object digest. Before writing the last chunk, compare the digest with the stored checksum. On a mismatch, log ERROR and abort without sending the last chunk.
6. A client disconnect stops the loop and is logged at DEBUG.

### Metadata GET, list and PATCH: `ObjectService`

- **GET metadata** is one read query. **List** is one query with `LIMIT`/`OFFSET` over `objects_by_status` plus one `COUNT(*)`; `total` may differ by a concurrent commit, which is accepted.
- **PATCH:** validate the body (400) → `[tx]` read the object (404; 409 if not COMMITTED), merge, validate the merged map (400, which rolls back), write `custom_metadata` and `updated_at` → return the GET metadata view. Because the transaction begins `IMMEDIATE`, two concurrent PATCHes run one after the other and neither loses its keys.

### Delete: `ObjectService` and `ObjectRemoval`

1. **D1.** Add the ID to `InFlightObjects` (removed again on 404 or 409). Then `[tx]` check the object (404; 409 if not COMMITTED), set DELETING, and turn every replica row into a tombstone `OBJECT_DELETED`. Two concurrent DELETEs are serialised by the write lock: the second sees DELETING and gets 409.
2. **D2.** For each of the object's tombstones on an ALIVE worker: `deleteChunk`. On confirmation: `[tx]` remove the tombstone. On failure: leave it for `TombstoneJob`.
3. **D3.** `[tx]` Delete the object row (chunk rows cascade; replica rows are already gone). Remove the ID from `InFlightObjects` and return 204.

### Membership: `Membership`

- **Register:** `[tx]` upsert the worker row (insert as SUSPECT; keep `registered_at`; update `url`). Record last-seen, then publish `WorkerRegistered`.
- **Heartbeat:** for an unknown worker, return 404. Otherwise record last-seen and stats, return 204, then handle `corrupt_chunks`: `[tx]` mark the listed HEALTHY replicas CORRUPT, then publish `ReplicaDegraded`.
- **`runOnce()`** (every 1 s): compute each worker's heartbeat age with the injected `Clock` and apply the worker transitions (§8). Each transition is `[tx]` persisted (state and `last_heartbeat_at`, plus HEALTHY → LOST on DEAD) before the in-memory state and the event change.
- **Startup:** load every worker as SUSPECT with last-seen = now.

### Repair: `RepairJob`

`runOnce()` selects at most 100 chunks of COMMITTED objects with fewer than 2 HEALTHY replicas on ALIVE workers, and submits each to `repairExecutor`. Per chunk:

1. Sources are HEALTHY replicas on ALIVE workers. With none, log WARN (the chunk is unavailable) and skip.
2. **In-place first.** For each LOST or CORRUPT replica on an ALIVE worker:
   1. `getChunk` from a source; verify against the metadata (a mismatch marks that source CORRUPT; try the next source).
   2. `putChunk` to the replica's worker.
   3. On confirmation: `[tx]` → HEALTHY.
3. **New copies while still under 2.**
   1. Placement picks a worker with no replica row and no tombstone for the chunk.
   2. **R1.** `[tx]` Insert PENDING.
   3. **R2.** `getChunk` and verify.
   4. **R3.** `putChunk`.
   5. **R4.** On confirmation: `[tx]` PENDING → HEALTHY. On failure or unknown outcome: `[tx]` PENDING → tombstone `WRITE_FAILED`, then try the next destination.
   6. With no destination left, log WARN (under-replicated) and leave it for the next run.

### Reconciliation: `Reconciler`

Runs per worker on `WorkerRegistered`, on DEAD → ALIVE, and every 300 s for each ALIVE worker:

1. Fetch the inventory. Any failure means skip and retry later; a worker is never treated as empty because its inventory could not be read.
2. **Startup gate.** Until every registered worker has delivered a complete inventory since controller start, or `STARTUP_GRACE_SECS` has passed, apply only steps that create no tombstone.
3. Compare the inventory with the metadata. PENDING rows are ignored, because they are in flight:

   | Metadata | Disk | Action |
   |---|---|---|
   | HEALTHY | Absent | `[tx]` → LOST, `ReplicaDegraded` |
   | HEALTHY | Recorded checksum ≠ chunk checksum | `[tx]` → CORRUPT, `ReplicaDegraded` |
   | LOST | Present, ≥ 2 HEALTHY elsewhere | *(gated)* `[tx]` re-check, then → tombstone `EXTRA_COPY` |
   | LOST | Present, fewer elsewhere | `getChunk` and verify: valid → `[tx]` HEALTHY; mismatch → `[tx]` CORRUPT |
   | LOST | Absent, ≥ 2 HEALTHY elsewhere | `[tx]` re-check, then delete the row |
   | LOST | Absent, fewer elsewhere | Leave it to repair (in-place rewrite) |
   | CORRUPT | Any, ≥ 2 HEALTHY elsewhere | *(gated)* `[tx]` re-check, then → tombstone `EXTRA_COPY` |
   | CORRUPT | Any, fewer elsewhere | Leave it to repair (in-place rewrite) |
   | No row, tombstone exists | Present | Nothing; `TombstoneJob` handles it |
   | No row, no tombstone, object COMMITTED or DELETING | Present | *(gated)* `[tx]` tombstone `UNTRACKED_COPY` |
   | No row, object UPLOADING | Present | Nothing |
   | Object absent | Present, or quarantined | *(gated)* `OrphanTracker` sighting (below) |

4. Trigger `TombstoneJob` if tombstones were added.

### Orphan cleanup: `OrphanTracker`

- A sighting is recorded the first time a complete inventory shows a file (servable or quarantined) whose object does not exist.
- A sighting is dropped when a later complete inventory no longer shows the file.
- When a complete inventory at least `GC_GRACE_SECS` after the first sighting still shows the file: with `GC_DRY_RUN=true`, log `would delete` at INFO; otherwise `[tx]` insert a tombstone `ORPHAN`.

Sightings live in memory: a controller restart only delays cleanup.

### Recovery: `RecoveryJob`

Runs at startup and then every 10 s:

- **UPLOADING objects not in `InFlightObjects`** → abort (as above).
- **DELETING objects not in `InFlightObjects`** → `[tx]` any remaining replica rows → tombstones, then D3.
- **At startup only:** PENDING replicas of COMMITTED objects → `[tx]` tombstone `REPAIR_INTERRUPTED` (no repair survives a restart).

### Tombstones: `TombstoneJob`

`runOnce()` processes tombstones whose worker is ALIVE, following the tombstone table in §8. The N5 check is one query that counts HEALTHY replicas on ALIVE workers for the chunk, excluding the tombstone's worker, immediately before the DELETE is sent.

### Worker: `ChunkStore`, `ChunkApi`, `ControllerLink`, `Scrubber`

- **Boot.**
  1. Create `DATA_DIR/chunks` and increment `boot_count` (temp + `force` + rename).
  2. Delete leftover `*.tmp` files.
  3. Build the index by listing `chunks/**` and parsing file names; unrecognised names are logged at WARN and left alone.
  4. Start the web server and `ControllerLink`.
- **PUT, GET, DELETE, inventory:** see §12 and `docs/api.md` §5.
- **`ControllerLink`:** registers every heartbeat interval until accepted, then heartbeats with `chunk_count`, `used_bytes` and unreported corrupt chunk IDs. Corrupt IDs are cleared after a 2xx. A 404 sends it back to registering.
- **`Scrubber.runOnce()`:** for each indexed chunk in turn, take the chunk's lock, re-hash the file, quarantine on a mismatch (record it for the heartbeat), release the lock and pause briefly so heartbeats and requests stay responsive.

## 10. Failure handling

### 10.1 Classifying worker calls

`HttpWorkerClient` maps every outcome to a `Failure` kind. Callers act on the kind, never on raw exceptions.

| Outcome | `Failure` | Class |
|---|---|---|
| Connection refused, connect timeout, DNS failure | `UNREACHABLE` | Transient |
| Worker 503 `storage_unavailable` | `UNREACHABLE` | Transient |
| Request timeout, connection reset or I/O error after the request was sent; worker 500 | `OUTCOME_UNKNOWN` | Unknown outcome |
| Worker 404 `chunk_not_found` | `NOT_FOUND` | Permanent |
| Worker 409 `checksum_mismatch` (GET) | `CORRUPT` | Permanent |
| Worker 400 or 404 `worker_not_found` | `REJECTED` | Permanent; a controller bug, logged at ERROR |

The 5 s timeout must cover the whole call, including the body. Phase 3 proves this with a stalled stub server; if the JDK client's request timeout stops at the headers, `HttpWorkerClient` enforces an overall deadline.

### 10.2 Exceptions to HTTP responses

Each application's `ApiExceptionHandler`:

| Exception | Response | Log |
|---|---|---|
| `ApiException` | Its `ErrorCode` status and code, with its sanitised message | ERROR with stack trace for a 5xx that carries a cause (something underneath broke, such as the disk); WARN for other 5xx codes; DEBUG for 4xx |
| Multipart parse failure, unreadable or invalid JSON, type mismatch on a parameter, unsupported media type | 400 `invalid_request` | DEBUG |
| No handler for the path | 404 `not_found` | DEBUG |
| Wrong HTTP method | 405 `method_not_allowed` | DEBUG |
| `DataAccessException` | 500 `internal_error` | ERROR with stack trace |
| Any other exception | 500 `internal_error` | ERROR with stack trace |
| Client disconnected | No response | DEBUG |
| Anything after the response was committed | Abort the connection | ERROR (download) or DEBUG (client gone) |

Services throw `ApiException` for every expected client-visible failure. Internal code throws or translates exceptions with `new X(message, cause)`, so the cause is always kept (N7).

### 10.3 Failure walks

Each walk answers the seven questions of `CLAUDE.md` §5. "Crash after" means the process dies right after that point; §10.4 has the recovery outcome of every crash row.

**Upload.**

| Failure point | Saved so far | Then | Client gets | Log |
|---|---|---|---|---|
| < 2 ALIVE workers | Nothing | Return immediately | 503 `insufficient_workers` | DEBUG |
| Bad parts or metadata | Staged temp file | Spring removes the temp file | 400 | DEBUG |
| Staging disk full or I/O error | Partial temp file | Spring removes the temp file | 500 | ERROR |
| SQLite error at U2 | Nothing (rollback) | — | 500 | ERROR |
| One copy fails or has an unknown outcome (U5) | Object, chunk, PENDING rows | PENDING → tombstone; write to the next eligible worker | — | WARN with worker and chunk |
| No eligible worker for a second copy | Rows, some copies | Abort: tombstones, inline deletes, D3 | 503 `insufficient_workers` | WARN |
| Client disconnects mid-stream | Rows, some copies | Abort | — | INFO |
| SQLite error at U4, U6 or U7 | Earlier transactions | Abort; recovery finishes it if the abort fails too | 500 | ERROR |
| Crash after any step | See §10.4 | Recovery aborts the upload | Connection error | — |

**Download.**

| Failure point | Saved so far | Then | Client gets | Log |
|---|---|---|---|---|
| Unknown or not COMMITTED | — | — | 404 / 409 | DEBUG |
| No reachable copy for some chunk (pre-check) | — | — | 503 `chunk_unavailable` | WARN |
| One copy corrupt (worker 409 or controller mismatch) | — | `[tx]` CORRUPT, `ReplicaDegraded`, next copy | Correct bytes | WARN with chunk and worker |
| One copy missing (404) | — | `[tx]` LOST, `ReplicaDegraded`, next copy | Correct bytes | WARN |
| One copy unreachable or unknown | — | Next copy | Correct bytes | DEBUG |
| Every copy of a later chunk fails | Headers and earlier chunks sent | Abort the connection | Truncated body | ERROR |
| Whole-object checksum mismatch | All but the last chunk sent | Abort before the last chunk | Truncated body | ERROR (a metadata inconsistency) |
| Crash | Nothing changed except replica marks already committed | — | Truncated body | — |

**PATCH.** It can fail on invalid input (400, nothing saved), an unknown or uncommitted object (404 or 409), or an SQLite error (rollback, 500, ERROR). The only saved state is the single transaction, so after a crash either the old or the new metadata is visible.

**Delete.**

| Failure point | Saved so far | Then | Client gets | Log |
|---|---|---|---|---|
| Unknown or not COMMITTED | — | — | 404 / 409 | DEBUG |
| SQLite error at D1 | Nothing (rollback) | — | 500 | ERROR |
| A worker DELETE fails (D2) | DELETING, tombstones | Tombstone kept for `TombstoneJob` | — (still 204 after D3) | WARN |
| SQLite error at D3 | DELETING, tombstones | Recovery finishes D3 | 500 | ERROR |
| Crash after D1, D2 or D3 | See §10.4 | — | Retry: 409, then 404 | — |

**Register and heartbeat.** Invalid body → 400. SQLite error on register → 500, and the worker tries again next interval. Heartbeat from an unknown worker → 404, and the worker re-registers. A crash loses only in-memory last-seen times; startup marks everyone SUSPECT and heartbeats restore ALIVE within one interval.

**Membership check.** An SQLite error while persisting a transition leaves the in-memory state unchanged (ERROR), and the next run retries. The DEAD transition and LOST marking are one transaction, so a crash leaves either both or neither.

**Startup marking.** Controller startup marks every worker SUSPECT and keeps it SUSPECT until a registration or heartbeat arrives, so no worker is ALIVE on the strength of a heartbeat from before the restart. A worker that stays silent goes DEAD a full DEAD threshold after startup.

**Live inventory proxy.** Unknown worker → 404 `worker_not_found`. Any failed call to the worker (refused, timed out, stalled body, error status) → 503 `worker_unavailable`, logged once at WARN with the failure kind; nothing is saved, so a crash loses nothing.

**Worker heartbeat sender.** A failed register or heartbeat is repeated on the next run, one interval later; the first failure of an outage is logged at WARN and the recovery at INFO. A 404 on a heartbeat makes the next run register again. The `corrupt_chunks` list is cleared only after a 2xx, so a report lost in transit is sent again; a crash loses only that in-memory list, and the quarantined files remain for reconciliation. Shutdown sends nothing.

**Repair.**

| Failure point | Saved so far | Then | Log |
|---|---|---|---|
| No source | — | Skip; reported unavailable | WARN |
| Source fails verification | — | `[tx]` source → CORRUPT; next source | WARN |
| Destination write fails or unknown | PENDING | `[tx]` → tombstone; next destination | WARN |
| No destination | — | Skip; reported under-replicated | WARN |
| Crash after R1–R3 | PENDING | Startup → tombstone `REPAIR_INTERRUPTED`; the next run repairs again | — |

**Reconciliation.** An inventory failure or timeout → skip (WARN), never treated as empty. A failed verification read → leave the row as it is, retry next run. Every tombstone it creates is logged at INFO with its reason. A crash between its transactions leaves each row in an old or new valid state; the next run repeats the comparison.

**Tombstone job.** DELETE fails or is unreachable → keep the tombstone and log WARN (tombstones are only attempted on ALIVE workers, so this is rare); `pending_tombstones` on `/internal/replication` shows the backlog. Crash after DELETE but before removal → the next run sends DELETE again, which is idempotent. Adoption is one transaction.

**Orphan cleanup.** Inventory failure → no sighting change. Restart → sightings restart, which only delays deletion.

**Worker PUT.**

| Failure point | Saved so far | Then | Response | Log |
|---|---|---|---|---|
| Bad ID or header | — | — | 400 | DEBUG |
| Body over `CHUNK_SIZE`, or empty | Temp file | Stop reading; delete the temp file | 400 `invalid_chunk_size` | DEBUG |
| Body stream fails (client gone) | Temp file | Delete the temp file | 400 `invalid_request`, if anyone is left to read it | DEBUG |
| Body hash ≠ header | Temp file | Delete the temp file | 400 `checksum_mismatch` | WARN |
| Disk error or full | Temp file | Delete the temp file | 503 `storage_unavailable` | ERROR |
| Disk error after the rename (deleting other copies, forcing the directory) | New verified file, published and indexed | Nothing to undo; a repeated PUT finishes the cleanup | 503 `storage_unavailable` | ERROR |
| Crash before rename | Temp file | Boot deletes `*.tmp` | Connection error | — |
| Crash after rename, before reply | Complete verified file | Controller sees an unknown outcome → tombstone, then adopt or delete | Connection error | — |

**Worker GET.** File missing → 404. An indexed file missing from disk → drop it from the index, 404 (WARN with chunk ID). Read error → 503 (ERROR). Hash mismatch → quarantine (rename under the chunk's lock; the heartbeat reports it from Phase 3), 409 (WARN with chunk ID). A crash during quarantine leaves the file either servable (and still corrupt; detected again) or quarantined.

**Worker DELETE.** Disk error → 503 and nothing confirmed (ERROR). A crash after unlink but before the reply → the controller resends, which is idempotent.

**Worker inventory.** Built from the in-memory index, so only the scan for quarantined files touches the disk: a scan error → 500 `internal_error` (ERROR), never a partial list. A `worker_id` that is not this worker → 404 `worker_not_found` (DEBUG). It only reads, so a crash loses nothing.

**Worker boot.** `BootCounter` updates `DATA_DIR/boot_count` (temp + `force` + rename + directory `force`) before Tomcat accepts requests, so no client sees these failures: the worker just never becomes healthy, and nothing restarts it (A1). The worker never silently resets the count. A directory that cannot be listed fails startup.

| Failure point | Saved so far | Then | Response | Log |
|---|---|---|---|---|
| `boot_count` not a number, or negative | Old file | Stop startup; the file is left for an operator | Worker never healthy | ERROR naming the file |
| `boot_count` unreadable; `DATA_DIR` unwritable or full | Old file, maybe a partial `boot_count.tmp` | Stop startup | Worker never healthy | ERROR naming the directory |
| Crash before the rename | Old file, maybe `boot_count.tmp` | Next boot overwrites the temp file and counts from the old value | — | — |
| Crash after the rename, before the directory `force` | New file; the rename may not be durable yet | After a power loss the old count may come back, never a partial one | — | — |
| `chunks` directory cannot be created or listed | — | Stop startup | Worker never healthy | ERROR |
| Leftover `*.tmp` files | Unfinished writes | Delete them | — | INFO with the count |
| A file that is not `<chunk_id>.<sha256>` in its hashed directory | The file | Leave it alone and do not index it | — | WARN |
| Two servable files for one chunk ID (a crash in the middle of a PUT) | Both files | Index the newer by modification time, the greater file name on a tie; keep the other until the chunk is next written or deleted | — | WARN with chunk ID |

**Graceful shutdown.** `docker stop`, `docker compose stop`, `down` and `restart` send SIGTERM to the JVM, which is PID 1 because the `ENTRYPOINT` uses exec form. Shutdown never deregisters a worker, so a stopped worker looks the same as a failed one to the controller. A crash at any point is handled like any other failure, by recorded intent (N4) and recovery, never by relying on a clean shutdown.

| Failure point | Saved so far | Then | Response | Log |
|---|---|---|---|---|
| Requests in flight | Whatever each request had already recorded | Tomcat stops accepting connections; running requests get 5 s (`timeout-per-shutdown-phase`), then are cut off | Finished requests complete; cut-off ones see a connection error | INFO (Spring's graceful-shutdown lines) |
| Shutdown takes longer than Docker's 10 s stop timeout | Same | Docker sends SIGKILL; the container exits with 137 instead of 143 | Connection error | — |

**Healthcheck failure.** Compose runs `curl -fsS http://localhost:<port>/health` every 5 s with a 3 s timeout, after a 30 s start period.

| Failure point | Saved so far | Then | Response | Log |
|---|---|---|---|---|
| Application still starting | — | Failures in the start period do not count | `starting` in `docker compose ps` | — |
| Application failed to start, crashed or hangs | — | After 12 failures in a row the container is `unhealthy`; nothing restarts it (A1) | `unhealthy`; at `docker compose up`, workers wait for a healthy controller | Probe output in `docker inspect`; the application's own logs |

**Controller startup and metadata store.** Flyway runs before Tomcat accepts requests, so a startup failure is never seen by a client: the controller just never becomes healthy, and nothing restarts it (A1).

| Failure point | Saved so far | Then | Response | Log |
|---|---|---|---|---|
| Database file cannot be opened or created (missing directory, no permission) | — | Startup stops with `SQLITE_CANTOPEN` | Controller never healthy | ERROR (Spring's startup failure) |
| A migration fails | Earlier migrations | Flyway rolls the failed migration back, records no version for it, and stops startup | Controller never healthy | ERROR naming the migration |
| Write lock held past `busy_timeout` (5 s), or no free pooled connection within Hikari's `connection-timeout` (5 s) | Nothing from this transaction | Inside `TransactionTemplate` the failure comes at `BEGIN IMMEDIATE`, as `CannotCreateTransactionException` (a `TransactionException`, not a `DataAccessException`); a single statement outside a transaction throws `DataAccessException`. A request gets 500 `internal_error`; a background job tries again on its next run | 500 | ERROR with stack trace |
| A constraint rejects a write | Nothing (rollback) | A bug in the caller, never an expected outcome | 500 `internal_error` | ERROR with stack trace |
| The database stops answering after startup | Committed transactions | `/health` runs `SELECT 1` and reports `DOWN` | `/health` 503; other requests 500 | WARN from `/health`, without a stack trace because the probe repeats every 5 s |
| Crash in the middle of a transaction | Committed transactions only | SQLite discards the uncommitted WAL frames when the file is next opened | Connection error | — |
| Crash right after a commit | The committed transaction (`synchronous=FULL`) | Nothing | Connection error | — |

**Scrub.** A read error on one file → log ERROR and continue (the file stays in the index, and the next GET will fail and report it). Lock contention → wait; scrub holds each lock for about one file read.

### 10.4 Crash tables

Each row has a recovery test (`CLAUDE.md` §6).

**Upload**

| Step | Durable state after it | Recovery | Client sees |
|---|---|---|---|
| U1 check workers | Nothing | Nothing | Connection error; retry is safe |
| Staging | Temp file | Startup wipes the staging directory | Connection error |
| U2 object UPLOADING | Object row | `RecoveryJob`: UPLOADING → DELETING + tombstones, D3 | Connection error |
| U4 chunk + 2 PENDING | Intent | As above; `TombstoneJob` deletes the copies | Connection error |
| U5 copies written | Bytes on workers | As above | Connection error |
| U6 HEALTHY or tombstone + new PENDING | Rows | As above | Connection error |
| U7 COMMITTED | Complete object (`synchronous=FULL`) | Nothing | Lost 201; a retry creates a second object |

**Delete**

| Step | Durable state after it | Recovery | Client retry sees |
|---|---|---|---|
| D1 DELETING + tombstones | Intent | `RecoveryJob` runs D3; `TombstoneJob` deletes copies | 409 |
| D2 some tombstones removed | Fewer tombstones | Same | 409 |
| D3 rows removed | Remaining tombstones only | `TombstoneJob` | 404 |

**Repair**

| Step | Durable state after it | Recovery |
|---|---|---|
| R1 PENDING on destination | Intent | Startup: → tombstone `REPAIR_INTERRUPTED`, adopted or deleted per §8 |
| R2 source read | Same | Same |
| R3 copy written | Bytes on destination | Same (adopted if it is needed and valid) |
| R4 HEALTHY | Complete | Nothing |

**In-place repair of a CORRUPT or LOST row:** the row keeps its status until a verified overwrite is confirmed, so a crash means it is repaired again.

The window N5 cannot close: the "2 will remain" check commits before the DELETE is sent. If another worker fails in between, the chunk drops to 1 copy and repair restores it. It reaches 0 only if both other copies fail at once, a double failure that replication factor 2 does not promise to survive.

## 11. SQLite and transactions

Driver properties, set through `spring.datasource.hikari.data-source-properties` on every pooled connection:

| Property | Value | Why |
|---|---|---|
| `journal_mode` | `WAL` | Readers do not block the writer |
| `synchronous` | `FULL` | A committed 201 survives power loss (I1) |
| `foreign_keys` | `true` | SQLite enforces foreign keys only when each connection asks |
| `busy_timeout` | `5000` | Writers wait for the lock instead of failing at once |
| `transaction_mode` | `IMMEDIATE` | Every explicit transaction takes the write lock at `BEGIN`, so a read-then-write transaction cannot deadlock on a lock upgrade |

Phase 2 has a test that reads `PRAGMA foreign_keys`, `journal_mode` and `synchronous` on pooled connections.

Rules:

- Multi-statement changes run in `TransactionTemplate.execute`. Programmatic transactions keep boundaries visible and avoid the `@Transactional` self-invocation trap.
- Because every explicit transaction takes the write lock, reads run as single statements in autocommit mode. A view that needs several tables uses one query with joins.
- SQLite allows one writer at a time. Transactions are short (a few statements, no I/O), so `busy_timeout` is enough. `SQLITE_BUSY` after 5 s surfaces as a `DataAccessException`: 500 for a request, a logged failure and retry for a job.
- The pool has 4 connections.

Transaction catalogue, with every write the controller makes:

| Name | Statements |
|---|---|
| Upload start (U2) | Insert object |
| Chunk intent (U4) | Insert chunk, 2 PENDING replicas |
| Copy confirmed (U6, R4, in-place repair) | Replica → HEALTHY, `last_verified_at` |
| Copy failed (U6, R4) | Delete replica, insert tombstone, insert next PENDING (if any) |
| Commit (U7) | Update object to COMMITTED |
| Begin removal (D1, abort, recovery) | Object → DELETING; replicas → tombstones |
| Tombstone confirmed | Delete tombstone |
| Finish removal (D3) | Delete object (cascades chunks) |
| PATCH | Read object, update metadata and `updated_at` |
| Register | Upsert worker |
| Worker transition | Update worker; on DEAD, HEALTHY → LOST for its replicas |
| Mark degraded | Replica → CORRUPT or LOST |
| Repair intent (R1) | Insert PENDING |
| Extra copy | Re-check count, replica → tombstone |
| Untracked or orphan copy | Insert tombstone |
| Adopt | Delete tombstone, insert HEALTHY replica |

## 12. Worker storage

**Layout.**

```
${DATA_DIR}/boot_count
${DATA_DIR}/chunks/<aa>/<bb>/<chunk_id>.<sha256>              servable copy
${DATA_DIR}/chunks/<aa>/<bb>/<chunk_id>.<sha256>.corrupt      quarantined file
${DATA_DIR}/chunks/<aa>/<bb>/<chunk_id>.<random>.tmp          write in progress
```

`<aa>/<bb>` are the first 4 hex characters of SHA-256(`chunk_id`). Chunk IDs start with a UUID v7 timestamp, so their own prefix would put nearly every file in one directory. There is at most one servable file per chunk ID.

**Write (PUT).**

1. Validate the ID and the header.
2. Create the target directory if needed, forcing its parent directories so a new directory survives a crash, then create a temp file in it.
3. Stream the body into the temp file, hashing it on the way and refusing more than `CHUNK_SIZE` bytes, counted rather than taken from `Content-Length`.
4. Compare the hash with the header (a mismatch deletes the temp file and returns 400).
5. Call `FileChannel.force(true)`.
6. Under the chunk's lock:
   1. `Files.move(temp, final, ATOMIC_MOVE)`;
   2. update the index, so it matches the disk even if a later step fails;
   3. delete any other servable or quarantined file for that chunk ID;
   4. `force` the directory (`FileChannel.open(dir, READ).force(true)`).
7. The temp file is deleted in `finally` if anything failed. A failed write never touches the existing copy: other copies are deleted only after the new one is in place.

**Read (GET).** Under the chunk's lock, read the whole file (at most `CHUNK_SIZE` bytes) and hash it.

- If the hash matches the name, return the bytes.
- On a mismatch, rename the file to `.corrupt`, force the directory, remove it from the index, record it for the next heartbeat, and return 409.

**Delete.** Under the chunk's lock, delete the servable and quarantined files, force the directory, update the index, and return 204 even if nothing existed.

**Index.** `ConcurrentHashMap<chunkId, Entry(path, size, recordedChecksum)>`, rebuilt from file names at boot. The inventory is one pass over it. `chunk_count` and `used_bytes` come from it. Quarantined IDs come from a directory scan done with the inventory.

**Integrity rules (A6).** The name is the expected checksum, never proof. The contents are re-hashed on every GET, every scrub and every verification read. The controller checks again against its own metadata.

## 13. Observability and security

- **Logs.** Spring Boot structured JSON (`ecs`). SLF4J fluent key-values carry `operation`, `object_id`, `chunk_id` and `worker_id`. Every delete is logged at INFO with its tombstone reason; every state change at INFO; levels follow `CLAUDE.md` §5.
- **Health.** `/health` on both applications backs the Compose healthchecks.
- **Cluster view.** `/internal/workers` and `/internal/replication`, read through `scripts/cluster-status.sh` and `objstore status`.
- **Security scope.** There is no authentication (extension X4). The controller's internal endpoints are reachable through its published port, which is acceptable for a local course deployment and stated in the README.
  - Workers are reachable only on the Compose network.
  - Chunk ID validation stops path traversal on workers.
  - Error messages are sanitised (N8).
  - Custom metadata values and chunk bytes are never logged.
