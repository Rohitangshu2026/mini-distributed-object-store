# Roadmap

The plan for each of the 11 phases. Each phase leaves a buildable, tested increment. `CLAUDE.md` §15 has the summary table; this file is the detail. A phase starts with the checklist "Before implementing" (`CLAUDE.md` §3) and ends with the checklist "Before reporting complete".

Every phase uses the same headings:

| Heading | Answers |
|---|---|
| Objective | Why the phase exists |
| Builds | Features delivered |
| Code | Modules, packages and classes (names from `docs/architecture.md` §2) |
| Decisions already made | Settled choices, so the phase does not re-decide them |
| Depends on | Earlier phases |
| Failure walk | Operations introduced or changed; their walks live in `docs/architecture.md` §10 |
| Tests | By category (`CLAUDE.md` §6) |
| Docker and deployment | Image, Compose and script changes |
| Exit criteria | What must be true |
| Proof | Commands whose output goes in the report |
| Risks and common mistakes | What to watch for |

Shell snippets assume the repository root and a JDK 21 on the PATH (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS). Scripts take `CHUNK_SIZE` from `scripts/lib.sh`, which must equal `Chunking.CHUNK_SIZE`.

---

## Phase 0: Skeleton and topology

**Objective.** A buildable multi-module project and the brief's Docker topology, with no business logic, so every later phase starts from a working build, image and cluster.

**Builds.**

- **Git.** `git init -b main`, `.gitignore`, remote `origin` = `https://github.com/Rohitangshu2026/mini-distributed-object-store.git`. First commit on `main`: the existing docs. Then branch `phase-0-skeleton`.
- **Maven.**
  - Maven Wrapper pinned to 3.9.11.
  - Root POM:
    - parent `spring-boot-starter-parent` 4.1.2;
    - `java.version` 21;
    - modules `common`, `controller`, `worker`, `cli`, plus `e2e` in profile `e2e`.
  - Build plugins:
    - Spotless with google-java-format;
    - compiler with `-Xlint:all -Werror` and Error Prone (`.mvn/jvm.config`);
    - Enforcer (Java [21,22), Maven ≥ 3.9);
    - Surefire for `*Test`, Failsafe for `*IT`;
    - `spring-boot-maven-plugin` with `build-info`.
  - `dependencyManagement` for picocli and jqwik.
- **`common`.** `Chunking` with `CHUNK_SIZE` and `chunkCount(long size)`. A test-jar.
- **`controller`.** `ControllerApplication` and `HealthApi` (`/health`, with the database check added in Phase 2). JSON logging, graceful shutdown.
- **`worker`.** `WorkerApplication`, `WorkerProperties`, `BootCounter`, and `HealthApi` (`worker_id`, version, `boot_count`, `chunk_count` = 0).
- **`cli`.** `ObjstoreCli` with `--help` and `--version`.
- **`e2e`.** `HealthIT` checks controller `/health`.
- **Deployment files.** `Dockerfile`, `docker-compose.yml`, `Makefile`, `.dockerignore`.
- **CI.** `.github/workflows/ci.yml`: Temurin 21, Maven cache, `./mvnw -B verify`, `docker compose build`.
- **`README.md` skeleton.** Prerequisites, build, start and stop, status. The other R11 sections are listed with the phase that fills them.

**Code.** `common`: `Chunking`. `controller`: `ControllerApplication`, `api/HealthApi`. `worker`: `WorkerApplication`, `config/WorkerProperties`, `store/BootCounter`, `api/HealthApi`. `cli`: `ObjstoreCli`. `e2e`: `HealthIT`.

**Decisions already made.**

- Root parent is `spring-boot-starter-parent` 4.1.2; plain fat jars with no layered extraction.
- Images:
  - `eclipse-temurin:21-jdk` build stage running `./mvnw -B package -DskipTests` with a BuildKit cache mount for `/root/.m2`;
  - `eclipse-temurin:21-jre` runtime targets `controller`, `worker` and `client`.
  - The `client` target adds `jq` and an `objstore` wrapper script: `exec java -jar /app/objstore-cli.jar "$@"`.
- Runtime command: exec-form `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]`.
- Compose:
  - healthchecks `curl -fsS http://localhost:<port>/health`, interval 5 s, timeout 3 s, retries 12, start period 30 s;
  - workers `depends_on` controller `service_healthy`;
  - no `restart` key on any service;
  - `JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-}` passed to controller and workers.
- `/health` version comes from Spring Boot `BuildProperties`.
- `BootCounter`:
  - a missing file means first boot (1);
  - unreadable or invalid content fails startup;
  - writes use temp + `force` + rename.
- Whether `.agents/` and `.claude/` (skills folders) are committed is asked in the Phase 0 plan.

**Depends on.** Nothing.

**Failure walk.** Worker boot (`BootCounter`); graceful shutdown on SIGTERM; healthcheck failure. See `docs/architecture.md` §10.3 "Worker boot".

**Tests.**

| Category | Tests |
|---|---|
| Normal | `BootCounterTest`: first boot is 1, then 2; `HealthApiIT` on controller and worker (random port); CLI `--version` exits 0 |
| Boundary | `ChunkingTest` (jqwik): `chunkCount` for 0, 1, `CHUNK_SIZE`, `CHUNK_SIZE + 1`, large; equals the ceiling of size / `CHUNK_SIZE` |
| Invalid input | `BootCounterTest`: non-numeric or negative content fails with a clear message |
| Expected failure | `BootCounterTest`: unwritable directory fails startup |
| Recovery and restart | `BootCounterTest`: a leftover temp file does not change the count |
| Partial failure, failover, concurrency | Not applicable: no distributed behaviour yet |

**Docker and deployment.** Everything above. The Compose topology is final from this phase on (A1).

**Exit criteria.**

- `./mvnw -B verify` passes from a clean checkout.
- `docker compose up -d --build` leaves 5 healthy services.
- `docker compose restart` increases every worker's `boot_count` by 1.
- `docker compose down` finishes in a few seconds, and containers exit with 143 (SIGTERM), not 137 (SIGKILL).
- **jqwik gate:** a property ran under the Spring Boot JUnit 6 platform, and a temporary failing property showed a shrunk counterexample (output captured, then removed).
- **Error Prone gate:** a temporary `String ==` comparison failed the build (output captured, then removed).

**Proof.**

```bash
./mvnw -B verify
docker compose up -d --build && docker compose ps
for w in worker1 worker2 worker3 worker4; do docker compose run --rm client curl -fsS http://$w:9000/health; done
docker compose restart && sleep 20
for w in worker1 worker2 worker3 worker4; do docker compose run --rm client curl -fsS http://$w:9000/health | jq .boot_count; done
time docker compose down && docker ps -a --filter name=worker --format '{{.Names}} {{.Status}}'
```

**Risks and common mistakes.**

- JDK 17 is the default on this machine; the Enforcer must fail fast.
- A shell-form `ENTRYPOINT` makes `/bin/sh` PID 1, which swallows SIGTERM.
- Error Prone needs `.mvn/jvm.config`.
- Spring Boot 4 starters are modular: use `spring-boot-starter-webmvc`.
- Five JVMs need about 1.5 GB of Docker memory.
- A `start_period` that is too short marks slow JVM starts unhealthy.
- If jqwik fails the gate, switch to the fallback recorded in decision 0002 instead of debugging the engine.

---

## Phase 1: Worker chunk storage

**Objective.** A worker that stores, verifies and serves chunks durably, behind the brief's internal chunk API.

**Builds.**

- **`ChunkStore`.** Layout, write protocol, verify on read, quarantine, delete (including quarantined files), striped locks, index rebuilt at boot, `*.tmp` cleanup, inventory.
- **Worker endpoints.** `PUT`, `GET` and `DELETE` on `/internal/chunks/{chunk_id}`; `GET /internal/workers/{worker_id}/chunks`.
- **Error model.** `ErrorCode`, `ApiException`, `ErrorBody` in `common`, and the worker's `ApiExceptionHandler`.
- **`/health`.** Real `chunk_count`.

**Code.**

- `common`: `ChunkId`, `Checksums`, `error/*`, and `api/ChunkInfo` (used for both the PUT response and inventory entries), `api/Inventory`.
- `worker`: `store/ChunkStore`, `api/ChunkApi`, `api/ApiExceptionHandler`.

**Decisions already made.** Everything in `docs/architecture.md` §12, plus:

- PUT reads the raw request stream (no Spring body conversion) and returns 200 with `ChunkInfo`.
- GET returns `byte[]` with explicit headers.
- DELETE returns 204 every time.
- 64 lock stripes by `chunkId.hashCode()`.
- Error codes per `docs/api.md` §2.

**Depends on.** Phase 0.

**Failure walk.** Worker PUT, GET, DELETE, inventory and boot (`docs/architecture.md` §10.3).

**Tests.**

| Category | Tests (`ChunkStoreTest` on `@TempDir`, `ChunkApiIT` on a random port) |
|---|---|
| Normal | PUT then GET returns identical bytes; inventory lists the chunk with size and recorded checksum; DELETE removes it |
| Boundary | Sizes 1, `CHUNK_SIZE`; `CHUNK_SIZE + 1` rejected; empty body rejected; index with 6 and 10 digits |
| Invalid input | Bad chunk ID (including `../` and uppercase); missing or malformed checksum header → 400; nothing written |
| Expected failure | Body ≠ header → 400 `checksum_mismatch`, no file and no temp left; flipped byte on disk → GET 409, file renamed `.corrupt`, index updated, then GET 404 |
| Partial failure | Disk error (read-only directory) → 503 `storage_unavailable`, temp removed |
| Failover and repeat | Same PUT twice → one file, both 200; DELETE twice → 204, 204; PUT after quarantine → servable again and the quarantined file is removed |
| Recovery and restart | Leftover `*.tmp` deleted at boot; index rebuilt from disk matches the pre-restart inventory; unrecognised file names left alone and logged |
| Concurrency | Concurrent PUTs of the same chunk → one valid file; GET during PUT returns old or new bytes, never partial; concurrent quarantine and PUT never quarantines the fresh file |

**Docker and deployment.** None beyond the volumes from Phase 0.

**Exit criteria.**

- From the `client` container, a full-size chunk PUT to worker1 and GET back matches `sha256sum`, and still does after `docker compose restart worker1` (the persistence proof).
- DELETE twice → 204, 204, then GET → 404.
- PUT with a wrong checksum → 400, and the inventory does not list the chunk.
- A flipped byte inside the worker → GET 409 and a `.corrupt` file.

**Proof.**

```bash
docker compose up -d --build
docker compose run --rm client sh -c '
  set -e; ID=00000000-0000-7000-8000-000000000000-000000
  head -c 1000000 /dev/urandom > /tmp/c; SUM=$(sha256sum /tmp/c | cut -d" " -f1); echo "$SUM"
  curl -fsS -X PUT -H "X-Checksum-SHA256: $SUM" --data-binary @/tmp/c http://worker1:9000/internal/chunks/$ID
  curl -fsS http://worker1:9000/internal/chunks/$ID | sha256sum'
docker compose restart worker1 && sleep 15
docker compose run --rm client sh -c 'curl -fsS http://worker1:9000/internal/chunks/00000000-0000-7000-8000-000000000000-000000 | sha256sum'
docker exec worker1 sh -c 'f=$(find /data/chunks -name "00000000-0000-7000-8000-000000000000-000000.*" ! -name "*.corrupt"); printf "\377" | dd of="$f" bs=1 seek=0 conv=notrunc'
docker compose run --rm client curl -s -o /dev/null -w "%{http_code}\n" http://worker1:9000/internal/chunks/00000000-0000-7000-8000-000000000000-000000
```

**Risks and common mistakes.**

- Renaming across directories (the temp file must live in the target directory).
- Forgetting to `force` the directory after the rename.
- Trusting `Content-Length` instead of counting bytes.
- Validating the chunk ID after building a path from it.
- Leaking temp files on exceptions (delete in `finally`).
- Treating the file name as proof of integrity (A6).
- Holding a lock while waiting on the network: never needed on the worker.

---

## Phase 2: Metadata store

**Objective.** A durable, constraint-checked metadata store that every later controller feature builds on.

**Builds.**

- SQLite DataSource with the driver properties in `docs/architecture.md` §11.
- Flyway `V1__init.sql`, copied from `docs/architecture.md` §7.
- Repositories with every query later phases need, as each phase needs it. Start with create/read for objects, chunks, replicas, workers and tombstones.
- `UuidV7` and `Timestamps` in `common`, and `MutableClock` in the `common` test-jar.
- `ControllerConfig` beans: `Clock`, `RandomGenerator`, `TransactionTemplate`.
- Controller `/health` checks `SELECT 1`.

**Code.**

- `controller`: `metadata/*` repositories, rows and enums; `config/ControllerConfig`, `config/ControllerProperties`; `src/main/resources/db/migration/V1__init.sql`.
- `common`: `UuidV7`, `Timestamps`; test-jar `MutableClock`.

**Decisions already made.** `JdbcClient`, `TransactionTemplate`, epoch-millisecond `INTEGER` timestamps, `STRICT` tables, pool size 4, `IMMEDIATE` transactions (decision 0003).

**Depends on.** Phase 0.

**Failure walk.**

- Startup with an unreachable or unwritable database file fails fast with ERROR.
- A failed migration fails startup, and Flyway leaves no partial version.
- `SQLITE_BUSY` beyond 5 s surfaces as `DataAccessException`.
- A constraint violation is a bug → 500.

**Tests.**

| Category | Tests |
|---|---|
| Normal | Each repository method against a real SQLite file (this replaces compile-time query checking) |
| Boundary | `UuidV7` (jqwik): version nibble 7, variant bits, time-ordered across milliseconds, unique over 100,000 IDs; `Timestamps` round trip with millisecond precision |
| Invalid input | FK violation, duplicate (`object_id`, `chunk_index`), duplicate replica PK (I4), bad status value, COMMITTED without size → all rejected by the database |
| Expected failure | An exception inside `TransactionTemplate` rolls back every statement |
| Recovery and restart | `MetadataReopenIT`: an object with 3 chunks and 6 replicas written, context closed, reopened on the same file, read back identical |
| Concurrency | Two concurrent write transactions both commit, one after the other; a PATCH-style read-modify-write under concurrency loses no update |
| Configuration | `PragmaIT`: `foreign_keys = 1`, `journal_mode = wal`, `synchronous = 2` on two different pooled connections |
| Partial failure, failover | Not applicable: no remote calls yet |

**Docker and deployment.** `controller-data` volume in use. `docker compose restart controller` shows Flyway reporting the schema up to date.

**Exit criteria.** `MetadataReopenIT` and `PragmaIT` pass; controller `/health` is UP in Docker before and after `docker compose restart controller`.

**Proof.**

```bash
./mvnw -B verify -pl controller -am
docker compose up -d --build controller && docker compose logs controller | grep -i flyway
docker compose restart controller && sleep 10 && docker compose logs controller | grep -i "up to date" && curl -fsS localhost:8080/health
```

**Risks and common mistakes.**

- Driver properties not reaching every pooled connection (the test catches this).
- Explicit read transactions taking the write lock.
- `STRICT` needs SQLite ≥ 3.37 (the bundled 3.53 is fine).
- Writing UUID v7 bits in the wrong order.
- Repositories opening their own transactions: callers own transaction boundaries.

---

## Phase 3: Membership

**Objective.** The controller knows which workers exist and whether each is ALIVE, SUSPECT or DEAD, and can call them through one well-classified client.

**Builds.**

- **Controller membership.** Register and heartbeat endpoints; `Membership` (in-memory last-seen, `runOnce()` every 1 s, persisted transitions, startup as SUSPECT, DEAD → LOST marking, `WorkerStateChanged` and `WorkerRegistered` events).
- **Controller views.** `GET /internal/workers`; `GET /internal/workers/{id}/chunks` (live proxy).
- **Worker client.** `WorkerClient` with all four methods; `HttpWorkerClient` with failure classification and timeouts; `FakeWorkerClient`.
- **Worker side.** `ControllerLink` (register until accepted, heartbeats, re-register on 404, `corrupt_chunks` from `ChunkStore` cleared after a 2xx). The controller handles `corrupt_chunks` by marking HEALTHY replicas CORRUPT and publishing `ReplicaDegraded`.
- **Scripts.** `scripts/lib.sh` (shared helpers and `CHUNK_SIZE`); `scripts/cluster-status.sh` (one-shot, or `--watch` with timestamps, optionally `--until <worker>=<STATE>` with a timeout).

**Code.**

- `controller`: `membership/Membership`, `membership/WorkerStateChanged`, `api/InternalApi`, `workerclient/*`; schedulers in `ControllerConfig`; test `FakeWorkerClient`.
- `worker`: `heartbeat/ControllerLink`, and the `heartbeatScheduler` in `WorkerConfig`.
- `common`: `api/RegisterRequest`, `RegisterResponse`, `HeartbeatRequest`, `WorkerView`, `WorkerList`.

**Decisions already made.**

- Only the membership check changes states (`docs/architecture.md` §8). Ages come from the controller's clock, never from worker timestamps.
- Registration inserts SUSPECT.
- `HttpWorkerClient` uses HTTP/1.1, connect 1 s, and a 5 s deadline for the whole call.
- Events go through Spring `ApplicationEventPublisher`.

**Depends on.** Phases 1 and 2.

**Failure walk.** Register, heartbeat, membership check, live inventory proxy, worker heartbeat sender (`docs/architecture.md` §10.3).

**Tests.**

| Category | Tests |
|---|---|
| Normal | `MembershipTest` (MutableClock): register → ALIVE at the next check; steady heartbeats stay ALIVE; `/internal/workers` view |
| Boundary | Age just under 9 s → ALIVE, exactly 9 s → SUSPECT, exactly 15 s → DEAD; custom `SUSPECT_AFTER_MISSED` and `DEAD_AFTER_MISSED` |
| Invalid input | Bad `worker_id` or URL → 400; invalid config (DEAD ≤ SUSPECT) fails startup |
| Expected failure | Heartbeat from an unknown worker → 404; live inventory of a stopped worker → 503 `worker_unavailable` |
| Partial failure | SQLite error during a transition leaves the in-memory state unchanged and is retried |
| Failover and repeat | `ControllerLinkTest` (`MockRestServiceServer`): registration repeats until 200; a 404 heartbeat leads to re-registration; corrupt IDs are kept until a 2xx |
| Recovery and restart | Controller restart → every worker SUSPECT, then ALIVE after one heartbeat; DEAD marks HEALTHY replicas LOST in the same transaction |
| Concurrency | Heartbeats from 4 threads while the check runs: no lost updates and transitions only from the checker thread |
| Client classification | `HttpWorkerClientIT` against a JDK `HttpServer` stub: connection refused → `UNREACHABLE`; 503 → `UNREACHABLE`; stall after headers and mid-body → `OUTCOME_UNKNOWN` within the 5 s deadline; 404 → `NOT_FOUND`; 409 → `CORRUPT`; 500 → `OUTCOME_UNKNOWN` |

**Docker and deployment.** `cluster-status.sh` and `lib.sh` (`curl`, `jq`, `docker` only).

**Exit criteria.**

- `docker stop worker2` → SUSPECT about 9 s and DEAD about 15 s after its last heartbeat.
- `docker start worker2` → ALIVE within a few seconds with no controller restart.
- `docker compose restart controller` → all workers ALIVE within one heartbeat interval.
- The live inventory works for a running worker and returns 503 for a stopped one.

**Proof.**

```bash
scripts/cluster-status.sh
docker stop worker2 && scripts/cluster-status.sh --watch --until worker2=DEAD
docker start worker2 && scripts/cluster-status.sh --watch --until worker2=ALIVE
curl -fsS localhost:8080/internal/workers/worker1/chunks | jq .
docker stop worker3 && curl -s localhost:8080/internal/workers/worker3/chunks | jq . ; docker start worker3
```

**Risks and common mistakes.**

- Heartbeat handling waiting on SQLite: it records last-seen in memory only.
- Changing state from the request thread.
- Off-by-one in missed-heartbeat maths.
- Deregistering on shutdown.
- The JDK client's request timeout not covering the body (the stall test proves it).
- HTTP/2 upgrade attempts against Tomcat (force HTTP/1.1).

---

## Phase 4: Upload

**Objective.** Objects are uploaded, chunked, placed and stored as two verified copies, with memory bounded at one chunk and every failure leaving recoverable state.

**Builds.**

- **Endpoint.** `POST /api/v1/objects` with lazy multipart, the 503 rule, and part and metadata validation.
- **`UploadService`.** U1–U7, failover to other workers, abort through `ObjectRemoval`.
- **`Placement`.** Fewest stored copies first, with a rotating tie-break.
- **Supporting classes.** `CustomMetadata`, `InFlightObjects`.
- **Recovery.** `RecoveryJob` for UPLOADING objects, plus wiping the staging directory before the web server starts.
- **Views and errors.** `?view=expected`; the controller's `ApiExceptionHandler`; `replicaWriteExecutor`.
- **Tombstones.** `ObjectRemoval.attemptDeletes` for the "being deleted or gone" branch. Tombstones of other kinds are only stored until Phase 8.

**Code.**

- `controller`: `api/ObjectApi`, `api/ApiExceptionHandler`, `object/UploadService`, `object/ObjectRemoval`, `object/CustomMetadata`, `object/InFlightObjects`, `placement/Placement`, `reconcile/RecoveryJob`.
- `common`: `api/ObjectSummary`, `api/ExpectedInventory`.

**Decisions already made.**

- Decision 0004 (staging).
- The first copy is written on the request thread and the second on `replicaWriteExecutor`.
- One reused `byte[CHUNK_SIZE]` per upload.
- The load snapshot is taken once per upload.
- Placement order: fewest replica rows, then `(position − chunk_index) mod n` over workers sorted by ID.
- An upload is never retried by the server.

**Depends on.** Phases 1–3.

**Failure walk.** Upload (all rows), staging, abort, recovery of UPLOADING objects (`docs/architecture.md` §10.3 and §10.4).

**Tests.**

| Category | Tests |
|---|---|
| Normal | `UploadServiceTest` with `FakeWorkerClient`: 5 × `CHUNK_SIZE` → 5 chunks, 10 HEALTHY rows on distinct workers, all 4 used, checksum correct |
| Boundary | 0 B (0 chunks, checksum of empty input), 1 B, `CHUNK_SIZE`, `CHUNK_SIZE + 1`, 50 × `CHUNK_SIZE`; `Placement` (jqwik): never the same worker twice, never a worker with a row or tombstone, spreads across all ALIVE workers |
| Invalid input | Missing `file`, two `file` parts, unknown part, metadata not an object, nested value, `null`, empty key, 129-character key, > 16,384 bytes, duplicate key → 400 with the right code and nothing stored |
| Expected failure | < 2 ALIVE → 503 before the body is staged; worker unreachable for all candidates → 503, no COMMITTED row |
| Partial failure | One worker fails a write → copy placed elsewhere, `WRITE_FAILED` tombstone; write applied but response lost → tombstone, copy elsewhere |
| Failover and repeat | Second failure on the replacement worker → next candidate; candidates exhausted → abort |
| Recovery and restart | Crash rows U2, U4, U5, U6: `RecoveryJob` turns UPLOADING into DELETING with tombstones and removes the object; staging directory wiped at startup; an upload in `InFlightObjects` is never aborted by recovery |
| Concurrency | 8 parallel uploads: all succeed and every chunk has 2 distinct workers; executor never exceeds its size |

**Docker and deployment.** `JAVA_TOOL_OPTIONS` passthrough (Phase 0) used for the memory proof. The README gets an "Uploading" section.

**Exit criteria.**

- Uploading 5 × `CHUNK_SIZE` bytes gives 5 chunks and 10 replicas, no chunk twice on one worker, and all 4 workers used (expected and live inventories agree).
- Uploads of 0 B, 1 B, `CHUNK_SIZE`, `CHUNK_SIZE + 1` and 50 × `CHUNK_SIZE` succeed with the right chunk counts.
- A 500 MB upload succeeds with the controller's heap capped at 128 MB.
- With 3 workers stopped, upload returns 503 `insufficient_workers`.

**Proof.**

```bash
source scripts/lib.sh
head -c $((5 * CHUNK_SIZE)) /dev/urandom > /tmp/five.bin
curl -sS -F file=@/tmp/five.bin localhost:8080/api/v1/objects | tee /tmp/up.json | jq .
for w in worker1 worker2 worker3 worker4; do curl -fsS "localhost:8080/internal/workers/$w/chunks?view=expected" | jq '.chunks | length'; done
for w in worker1 worker2 worker3 worker4; do curl -fsS "localhost:8080/internal/workers/$w/chunks" | jq '.chunks | length'; done
JAVA_TOOL_OPTIONS=-Xmx128m docker compose up -d controller && head -c 500000000 /dev/urandom > /tmp/big.bin && curl -sS -F file=@/tmp/big.bin localhost:8080/api/v1/objects | jq .chunk_count
docker stop worker2 worker3 worker4 && sleep 16 && curl -s -F file=@/tmp/five.bin localhost:8080/api/v1/objects | jq . ; docker start worker2 worker3 worker4
```

**Risks and common mistakes.**

- `@RequestPart` parameters parse the body before the 503 check (use the lazy request).
- `read()` returning partial reads (use `readNBytes`).
- Reusing the buffer while a write is still running.
- A transaction left open across a PUT.
- Adding to `InFlightObjects` after the U2 transaction.
- Not converting PENDING rows to tombstones on failure.
- Tomcat's part-count limit.
- Staging directory missing at the first upload.

---

## Phase 5: Read path

**Objective.** Objects download correctly and verified, metadata and listings are readable, and demos 1–3 run.

**Builds.**

- `GET /api/v1/objects/{id}` (`DownloadService`: pre-check, failover order, controller-side verification, CORRUPT and LOST marking with `ReplicaDegraded`, whole-object check before the last chunk, abort).
- `GET /api/v1/objects/{id}/metadata`.
- `GET /api/v1/objects` (paging).
- `scripts/demo-1-storage.sh`, `demo-2-chunking.sh`, `demo-3-replication.sh`.

**Code.**

- `controller`: `object/DownloadService`, `object/ObjectService` (GET metadata, list), `ObjectApi` endpoints.
- `common`: `api/ObjectMetadata`, `ChunkView`, `ReplicaView`, `ObjectList`, `ObjectListEntry`.

**Decisions already made.**

- Synchronous writes to the servlet output stream (no `StreamingResponseBody`).
- Each chunk is fully buffered and verified before any of its bytes are written.
- Transient worker failures never change replica status.
- Abort after headers means a truncated body (`docs/api.md` §3).

**Depends on.** Phase 4.

**Failure walk.** Download, metadata GET, list (`docs/architecture.md` §10.3).

**Tests.**

| Category | Tests |
|---|---|
| Normal | Round trip for 1 B, `CHUNK_SIZE + 1`, 20 × `CHUNK_SIZE` with matching checksum and headers; metadata shows chunks and replicas; list pages correctly |
| Boundary | 0-byte download → 200, empty body; `limit` 1 and 1000; `offset` past the end → empty list with correct total |
| Invalid input | Bad ID → 400; `limit` 0 or 1001, negative offset, non-numeric → 400 |
| Expected failure | Unknown → 404; UPLOADING → 409 (and metadata shows UPLOADING); every copy of some chunk unreachable before start → 503 |
| Partial failure | First copy corrupt (fake returns altered bytes) → CORRUPT marked, second copy used, bytes correct; worker 409 → CORRUPT; worker 404 → LOST; every copy of chunk 3 fails mid-stream → body truncated (`DownloadIT` with a real HTTP client sees fewer bytes than `Content-Length`) |
| Failover and repeat | ALIVE copies tried before SUSPECT copies; unreachable → next copy with no status change |
| Recovery and restart | Inconsistent metadata (whole-object checksum wrong) → aborted before the last chunk, ERROR logged |
| Concurrency | 4 concurrent downloads of the same object all correct |

**Docker and deployment.** Demo scripts 1–3, plus README sections "Downloading" and "Demos 1–3".

**Exit criteria.**

- A 20 MB `.pdf` file (random bytes, `application/pdf`) round-trips with matching `sha256sum`.
- Demos 1–3 exit 0 and print their evidence.
- `DownloadIT` proves truncation on mid-stream failure.

**Proof.**

```bash
head -c 20000000 /dev/urandom > /tmp/sample.pdf
ID=$(curl -sS -F file=@/tmp/sample.pdf localhost:8080/api/v1/objects | jq -r .object_id)
curl -sS -D - -o /tmp/out.pdf localhost:8080/api/v1/objects/$ID | grep -i -E 'content-type|content-length|x-checksum'
sha256sum /tmp/sample.pdf /tmp/out.pdf
scripts/demo-1-storage.sh && scripts/demo-2-chunking.sh && scripts/demo-3-replication.sh
```

**Risks and common mistakes.**

- Writing bytes before verification.
- Using `StreamingResponseBody` (async timeout).
- Holding the whole object in memory.
- Marking a copy CORRUPT on a timeout.
- Logging client disconnects as errors.
- An `int` `Content-Length` for large objects (use `long`).

---

## Phase 6: Metadata updates, delete and CLI

**Objective.** Metadata can be changed without re-uploading, objects can be deleted safely even with workers down, and the `objstore` CLI covers every client operation.

**Builds.**

- **PATCH.** Merge in one `IMMEDIATE` transaction; validation on the merged map.
- **DELETE.** D1–D3 through `ObjectRemoval`.
- **Recovery.** `RecoveryJob` for DELETING objects.
- **CLI.** `objstore` `put`, `get`, `ls`, `stat`, `meta`, `rm`:
  - `put` streams a multipart body from disk and sends `metadata` first;
  - `get` streams to a file or stdout, then checks `Content-Length` and `X-Checksum-SHA256`;
  - global `--url` (env `CONTROLLER_URL`, default `http://localhost:8080`);
  - exit codes: 0 OK, 1 failure, 2 usage.
- **Client image.** Ships the CLI.
- **Demo.** `scripts/demo-4-metadata.sh`.

**Code.**

- `controller`: `object/ObjectService` (patch, delete), `object/ObjectRemoval`, `reconcile/RecoveryJob`.
- `cli`: `ObjstoreCli`, `ControllerHttp`.

**Decisions already made.**

- Tombstone-first delete (decision 0006).
- Keys cannot be removed by PATCH.
- The CLI uses the JDK `HttpClient` with `BodyPublishers.concat` of the part headers, `ofFile`, and the closing boundary, so files are never loaded into memory.
- picocli method subcommands in one class.

**Depends on.** Phase 5.

**Failure walk.** PATCH, delete, recovery of DELETING objects; CLI errors (decoded `{error, message}` printed to stderr, exit 1).

**Tests.**

| Category | Tests |
|---|---|
| Normal | PATCH adds and updates keys; `updated_at` bumped; checksum, size and chunks unchanged; DELETE → 204, then GET 404, no replica rows, tombstones removed after confirmed deletes |
| Boundary | `{}` PATCH; merged map exactly 16,384 bytes accepted, one byte more rejected |
| Invalid input | Non-JSON, array body, nested value → 400; bad ID → 400 |
| Expected failure | PATCH or DELETE on UPLOADING → 409; on unknown → 404; second concurrent DELETE → 409 |
| Partial failure | DELETE with one worker DEAD → 204, its tombstones remain, other copies gone |
| Failover and repeat | Worker DELETE fails transiently → tombstone kept; repeated DELETE on a worker is harmless |
| Recovery and restart | Crash rows D1, D2, D3: recovery finishes the delete; a test asserts no object row disappears while replica rows remain |
| Concurrency | Two concurrent PATCHes with different keys → both keys present |
| CLI | `ObjstoreCliIT` against a controller on a random port with `FakeWorkerClient`: `put`/`get` round trip with checksum check, `ls` paging, `meta`, `rm`, error output and exit codes, truncated download → exit 1 |

**Docker and deployment.** `client` image runs `objstore`. Demo 4. README sections "Using the CLI" and "Demo 4".

**Exit criteria.**

- PATCH changes metadata, and the checksum and chunk list are identical before and after.
- DELETE → 204 and no chunk files of that object on any worker volume.
- DELETE with worker2 stopped → 204 and tombstones recorded for worker2.
- Demo 4 exits 0.
- CLI round trip through the `client` container.

**Proof.**

```bash
scripts/demo-4-metadata.sh
docker compose run --rm client sh -c 'head -c 3000001 /dev/urandom > /tmp/f && objstore put /tmp/f --meta team=cc | tee /tmp/p.json && objstore get $(jq -r .object_id /tmp/p.json) -o /tmp/g && sha256sum /tmp/f /tmp/g'
ID=$(curl -sS -F file=@/tmp/five.bin localhost:8080/api/v1/objects | jq -r .object_id)
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE localhost:8080/api/v1/objects/$ID
for w in worker1 worker2 worker3 worker4; do docker exec $w sh -c "find /data/chunks -name '$ID-*' | wc -l"; done
```

**Risks and common mistakes.**

- PATCH replacing instead of merging.
- Validating before merging only.
- Removing the object row before converting replica rows (the cascade would drop them silently).
- Returning 204 before D3.
- The CLI building multipart bodies in memory.
- Boundary strings that could appear in the data (use a random boundary).

---

## Phase 7: Re-replication

**Objective.** When a worker dies, objects stay readable and every affected chunk returns to two healthy copies (I3), with the state visible on `/internal/replication`.

**Builds.**

- **`RepairJob`.** In-place rewrite of LOST and CORRUPT rows on ALIVE workers, new copies with PENDING intent, source verification, `REPAIR_CONCURRENCY`, 30 s sweep, triggers from `WorkerStateChanged` (DEAD) and `ReplicaDegraded`.
- **Recovery.** `RecoveryJob` turns PENDING rows of COMMITTED objects into tombstones at startup.
- **Report.** `GET /internal/replication`.
- **CLI.** `objstore status`. `objstore verify` downloads and checks every object, then requires a fully replicated report; with `--allow-under-replicated` it checks only content (used while a worker is down).

**Code.**

- `controller`: `repair/RepairJob`, `repairExecutor` and `repairScheduler`, `api/InternalApi` (replication), `reconcile/RecoveryJob`.
- `cli`: `status`, `verify`.
- `common`: `api/ReplicationReport`.

**Decisions already made.**

- Only COMMITTED objects are repaired.
- Copies on SUSPECT workers do not count.
- Batches of at most 100 chunks per run.
- Placement excludes workers with a row or tombstone for the chunk.

**Depends on.** Phases 3–6.

**Failure walk.** Repair (`docs/architecture.md` §10.3), crash rows R1–R4 (§10.4).

**Tests.**

| Category | Tests |
|---|---|
| Normal | Worker DEAD → its chunks restored to 2 HEALTHY on other workers; report goes from under-replicated to healthy |
| Boundary | Exactly 2 ALIVE workers; chunk with 0 healthy copies → reported unavailable, never healthy (I3) |
| Invalid input | Not applicable: no external input |
| Expected failure | No destination → under-replicated and retried next run; no source → unavailable |
| Partial failure | Source copy corrupt → marked CORRUPT, next source used; destination write fails → tombstone and next destination; unknown outcome → tombstone |
| Failover and repeat | Trigger coalescing (several triggers → one extra run); the next run picks up skipped chunks |
| Recovery and restart | Crash rows R1–R4 |
| Concurrency | `FakeWorkerClient` records at most `REPAIR_CONCURRENCY` repair calls in flight; I4 never violated under concurrent repair and upload |

**Docker and deployment.** README section "Cluster status". `objstore verify` used by later demos.

**Exit criteria.**

- With worker2 stopped, every object downloads (`objstore verify` content check).
- After DEAD plus repair time, `/internal/replication` reports `under_replicated: 0` and `unavailable: 0` with copies only on worker1, worker3 and worker4.
- No chunk has two copies on one worker.

**Proof.**

```bash
source scripts/lib.sh
for i in 1 2 3; do head -c $((3 * CHUNK_SIZE + i)) /dev/urandom > /tmp/o$i; curl -sS -F file=@/tmp/o$i localhost:8080/api/v1/objects > /dev/null; done
curl -fsS localhost:8080/internal/replication | jq '{healthy, under_replicated, unavailable}'
docker stop worker2 && sleep 20 && docker compose run --rm client objstore verify --allow-under-replicated
sleep 30 && curl -fsS localhost:8080/internal/replication | jq '{healthy, under_replicated, unavailable}'
docker start worker2
```

**Risks and common mistakes.**

- Repairing chunks of UPLOADING or DELETING objects.
- Counting SUSPECT copies as healthy.
- Placing on a worker with a tombstone.
- A transaction spanning the copy.
- Unbounded repair fan-out.
- Logging once per chunk per run (summarise instead).

---

## Phase 8: Rejoin, tombstones and cleanup

**Objective.** A returning worker is reconciled safely, tombstones are processed under N5, orphans are cleaned up (I5), and interrupted operations always finish. Demo 5 runs.

**Builds.**

- **`Reconciler`.** Complete inventories only, startup gate, the comparison table in `docs/architecture.md` §9; triggered by registration, DEAD → ALIVE, and every 300 s.
- **`TombstoneJob`.** The full rule table in §8, including adoption.
- **`OrphanTracker`.** Two sightings across `GC_GRACE_SECS`, and `GC_DRY_RUN`.
- **`RecoveryJob`.** Periodic run.
- **Report.** `pending_tombstones` in the replication report.
- **Demo.** `scripts/demo-5-failure.sh`.

**Code.** `controller`: `reconcile/Reconciler`, `reconcile/TombstoneJob`, `reconcile/OrphanTracker`, `reconcile/RecoveryJob`.

**Decisions already made.** Decision 0006; N5 wording; startup gate; orphan sightings kept in memory.

**Depends on.** Phase 7.

**Failure walk.** Reconciliation, tombstone job, orphan cleanup, recovery (`docs/architecture.md` §10.3), and every crash row in §10.4.

**Tests.**

| Category | Tests |
|---|---|
| Normal | Worker returns after repair → its LOST copies become tombstones and are deleted; exactly 2 copies per chunk remain; returning worker with still-needed copies → copies verified and re-adopted |
| Boundary | Exactly 2 HEALTHY elsewhere → delete allowed; 1 elsewhere → no delete (N5) |
| Invalid input | Inventory with malformed entries → skipped and logged |
| Expected failure | Inventory timeout or error → no changes at all (no mass LOST, no tombstones) |
| Partial failure | Tombstone whose copy fails verification while under 2 → tombstone dropped, never deleted; DELETE fails → tombstone kept |
| Failover and repeat | Repeated DELETE after a crash is harmless; UPLOADING tombstones wait until commit or abort |
| Recovery and restart | Every crash row (U, D, R); startup gate blocks tombstone creation until all inventories arrive or the grace period passes |
| Concurrency | Tombstone processing vs repair placement on the same chunk never deletes a fresh copy |
| Orphans | First sighting only records; second sighting after the grace period → tombstone; dry run only logs; quarantined files of unknown chunks included; chunk of an UPLOADING object never treated as orphan |

**Docker and deployment.** Demo 5. README sections "Worker failure demo" and "Troubleshooting" (first part).

**Exit criteria.**

- After `docker start worker2`, the report is fully replicated, and the live inventories hold exactly `2 × total_chunks` servable files.
- A controller killed mid-upload (`docker kill controller` during a 300 MB upload, then `docker start controller`) leaves no UPLOADING object and no leftover chunk files once tombstones are processed.
- A tombstone for a stopped worker is processed after it returns.
- A planted orphan file is removed after the grace period (run with `GC_GRACE_SECS=20` and two registrations at least 20 s apart).
- Demo 5 exits 0.

**Proof.**

```bash
scripts/demo-5-failure.sh
head -c 300000000 /dev/urandom > /tmp/big.bin; (curl -sS -F file=@/tmp/big.bin localhost:8080/api/v1/objects &) ; sleep 3; docker kill controller; docker start controller; sleep 30
curl -fsS localhost:8080/internal/replication | jq '{under_replicated, unavailable, pending_tombstones}'
for w in worker1 worker2 worker3 worker4; do curl -fsS localhost:8080/internal/workers/$w/chunks | jq '.chunks | length'; done
```

**Risks and common mistakes.**

- Treating a failed inventory as empty (mass deletion).
- Creating tombstones during the startup gate.
- Deleting without the N5 re-check.
- Forgetting that a tombstone on a live chunk may need adoption.
- An orphan check that ignores UPLOADING objects.
- `GC_GRACE_SECS` too long for the orphan test: override it, do not shorten the default.

---

## Phase 9: Integrity

**Objective.** Corruption is found with or without a download, never served, and repaired back to two valid copies. Demo 6 runs.

**Builds.**

- **`Scrubber`.** Sequential, throttled, lock per chunk, quarantine and report through the heartbeat (Phase 3).
- **End-to-end path.** Corrupt report or download detection → CORRUPT → in-place repair (Phase 7) → HEALTHY.
- **Scripts.** `scripts/corrupt-chunk.sh` and `scripts/demo-6-integrity.sh`.

**Code.** `worker`: `scrub/Scrubber`, `scrubScheduler`.

**Decisions already made.**

- A copy that fails verification is quarantined, never deleted on the spot (A6).
- The scrubber holds each chunk's lock only while hashing that chunk.
- `SCRUB_INTERVAL_SECS` can be lowered for tests, never in the default.

**Depends on.** Phases 1, 3, 7 and 8.

**Failure walk.** Scrub; worker GET quarantine; heartbeat report (`docs/architecture.md` §10.3).

**Tests.**

| Category | Tests |
|---|---|
| Normal | Scrub finds a flipped byte → quarantined, reported, controller marks CORRUPT, repair restores HEALTHY |
| Boundary | Corruption in the last byte of a `CHUNK_SIZE` chunk and in a 1-byte chunk |
| Invalid input | Truncated file (size differs) → treated as corrupt |
| Expected failure | Read error during scrub → ERROR logged, scrub continues |
| Partial failure | Both copies corrupt → chunk reported unavailable, download aborts, nothing corrupt served |
| Failover and repeat | Report kept until a 2xx heartbeat; repeated reports are harmless |
| Recovery and restart | Worker restart between quarantine and report → quarantined file still listed in inventory and handled by reconciliation |
| Concurrency | Scrub racing a repair PUT never quarantines the fresh file; heartbeats keep their interval while a scrub runs |

**Docker and deployment.** Demo 6, `corrupt-chunk.sh`, README sections "Integrity demo" and the rest of "Troubleshooting".

**Exit criteria.**

- After deliberate corruption, the download is correct, and logs name the chunk and the worker.
- `/internal/replication` returns to fully replicated, with 2 valid copies.
- With `SCRUB_INTERVAL_SECS=10`, corruption is found and repaired with no download.
- Demo 6 exits 0.

**Proof.**

```bash
scripts/demo-6-integrity.sh
SCRUB_INTERVAL_SECS=10 docker compose up -d worker1 && scripts/corrupt-chunk.sh worker1 && sleep 40 && curl -fsS localhost:8080/internal/replication | jq '{healthy, under_replicated, unavailable}'
docker compose logs controller worker1 | grep -i -E 'corrupt|quarantin'
```

**Risks and common mistakes.**

- Scrub starving request threads (throttle, one file at a time).
- Quarantining a file that was just replaced.
- Clearing reports before the controller acknowledged them.
- Demo 6 corrupting a quarantined file instead of the servable one.

---

## Phase 10: Finish

**Objective.** A clean clone reaches a passing demo run with no manual steps, and every R11 section is documented.

**Builds.**

- **`README.md`** complete (R11): prerequisites, build, start and stop, how clients connect (`client` container and host `curl`), upload and download, CLI, demos 1–6, troubleshooting, the "1 or more clients" explanation, the security scope.
- **Docs.** Final pass over `docs/architecture.md`, trimming the DDL section to point at the migration.
- **e2e suite.** `StorageIT`, `ChunkingIT`, `ReplicationIT`, `MetadataIT`, `FailureIT`, `IntegrityIT`, `ApiContractIT` (every error code and status in `docs/api.md`).
- **Benchmarks.** `docs/benchmarks.md`: upload and download throughput at 1, 10 and 100 × `CHUNK_SIZE`; time to restore replication factor 2 after losing a worker with N chunks. Method and hardware stated.
- **Demo target.** `make demo` runs demos 1–6 in order.
- **CI.** A job that starts the stack and runs the demos.

**Code.** `e2e/*`, scripts, docs. No new production behaviour; bugs found here get regression tests.

**Decisions already made.** Demos stay `curl`/`jq`/`docker` only; e2e tests may call `docker` through `ProcessBuilder` for the failure scenarios.

**Depends on.** Phases 0–9.

**Failure walk.** No new operations.

**Tests.** The e2e suite above, and the full `./mvnw -B verify`.

**Docker and deployment.** CI demo job.

**Exit criteria.**

- A fresh clone in a new directory → `docker compose up -d --build` → `make demo` passes demos 1–6 with no manual steps.
- `./mvnw -B -Pe2e -pl e2e -am verify` passes.
- The README walkthrough works verbatim.
- R1–R12 ticked in `CLAUDE.md` §16.

**Proof.**

```bash
git clone https://github.com/Rohitangshu2026/mini-distributed-object-store.git /tmp/clean && cd /tmp/clean
docker compose up -d --build && make demo
./mvnw -B -Pe2e -pl e2e -am verify
```

**Risks and common mistakes.**

- Demos depending on state left by earlier demos (each demo creates its own objects).
- Timing assumptions in scripts (poll with timeouts instead of fixed sleeps).
- README commands that drift from the scripts.
