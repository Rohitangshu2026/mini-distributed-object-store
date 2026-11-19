# mini-distributed-object-store

A small distributed object store, built for a Cloud Computing (Infrastructure) course. One
controller and four storage workers run under Docker Compose. Objects of any type are split into
1 MB chunks, and every chunk is stored on two different workers. The store keeps serving data when
a worker fails, restores two copies of every chunk on the remaining workers, never returns corrupted
bytes, and repairs corrupted copies.

## Contents

- [What it guarantees](#what-it-guarantees)
- [Architecture at a glance](#architecture-at-a-glance)
- [Prerequisites](#prerequisites)
- [Build and test](#build-and-test)
- [Start and stop the cluster](#start-and-stop-the-cluster)
- [How clients connect](#how-clients-connect)
- [Uploading and downloading objects](#uploading-and-downloading-objects)
- [Using the CLI](#using-the-cli)
- [Web UI](#web-ui)
- [Cluster status and replication](#cluster-status-and-replication)
- [Running demos 1 to 6](#running-demos-1-to-6)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Security scope and limitations](#security-scope-and-limitations)
- [Documentation](#documentation)

## What it guarantees

- **Every object is split into 1,000,000-byte chunks** (the last one may be smaller), each with an ID
  `{object_id}-{chunk_index:06}`, stored in order.
- **Two copies of every chunk, on two different workers**, each verified by its worker before the
  upload succeeds. A `201` means every chunk has both copies.
- **Corrupted data is never served.** Workers re-hash a chunk on every read and every scrub; the
  controller checks every chunk against its own checksum again before sending a byte, and checks the
  whole object's checksum before the last chunk.
- **A failed worker is detected** (SUSPECT after 9 s without a heartbeat, DEAD after 15 s), objects
  stay downloadable from the remaining copies, and repair restores two copies on the other workers.
- **A returning worker is reconciled** with the metadata: copies it still holds are verified and
  re-used or removed, so every chunk ends with exactly two copies.
- **Corrupt copies are repaired.** A copy that fails verification is quarantined (renamed, never
  deleted on the spot) and rewritten from a valid copy.
- **No copy is ever deleted while it might be one of the last two good ones**, and every delete
  first records its intent, so a crash at any point leaves state that recovery finishes.

## Architecture at a glance

```
client (objstore CLI / curl) ──REST──▶ controller :8080 ──internal HTTP──▶ worker1..worker4 :9000
                                        │                                    │
                                        └─ SQLite metadata (controller-data)  └─ chunk files (workerN-data)
```

- **Controller** (Spring Boot): the client API, all metadata in SQLite, placement, membership
  (heartbeats), repair, reconciliation, tombstones and orphan cleanup. It is the only component that
  knows what an object is.
- **Workers** (Spring Boot): store, verify and serve chunk files on their own volumes, report a
  heartbeat every 3 s, and scrub their chunks for corruption every 5 minutes.
- **Client**: the `objstore` command-line tool (plain Java), or any HTTP client such as `curl`.

Diagrams of every part are in [docs/diagrams.md](docs/diagrams.md). The full design is in [docs/architecture.md](docs/architecture.md) and every endpoint in
[docs/api.md](docs/api.md).

## Prerequisites

- **Docker** with Compose v2, and about 1.5 GB of memory for the five JVMs.
- **On the host, for the demos:** `bash`, `curl`, `jq` and `make`.
- **Only to build and test outside Docker:** JDK 21 as `JAVA_HOME` (the build stops on any other
  version). The Maven Wrapper (`./mvnw`) downloads Maven itself.

## Build and test

The cluster builds inside Docker, so you can skip this section if you only want to run it.

```bash
./mvnw -B verify
```

This checks formatting (google-java-format), compiles with `-Xlint:all -Werror` and Error Prone,
and runs the unit, property and integration tests (about 270 tests, real SQLite files and real HTTP,
with the workers faked where a failure must be injected). `./mvnw spotless:apply` fixes formatting.

With the cluster running, `make e2e` (or `./mvnw -B -Pe2e -pl e2e -am verify`) also runs the
end-to-end tests against it.

## Start and stop the cluster

```bash
docker compose up -d --build   # or: make up, which also waits until every service is healthy
docker compose ps              # controller and worker1 to worker4, all (healthy)
docker compose down            # stop; stored data stays in the volumes
docker compose down -v         # stop and DELETE all stored data (make reset)
```

The first build downloads the Maven dependencies and takes a few minutes; later builds are fast.

- Only the controller is reachable from the host, on port **8080**. The workers are only on the
  Compose network.
- Each worker keeps its chunk files in its own named volume (`worker1-data` to `worker4-data`), and
  the controller keeps its SQLite database in `controller-data`. Data survives `down` and restarts.
- No service restarts by itself, so `docker stop worker2` behaves like a failed worker until
  `docker start worker2`.

Check that everything is up:

```bash
curl -fsS localhost:8080/health
scripts/cluster-status.sh
```

## How clients connect

Clients reach the store only through the controller's REST API at `http://localhost:8080` (from the
host) or `http://controller:8080` (inside the Compose network). There are two kinds of client, and
any number of either can run at once:

- **`curl` (or any HTTP client) on the host**, against `http://localhost:8080`.
- **The `client` container**, which has the `objstore` CLI, `curl` and `jq`. It is defined in
  `docker-compose.yml` under the `client` Compose profile and starts on demand:

  ```bash
  docker compose run --rm client objstore ls
  ```

  The first `docker compose run` builds the client image, and its build output appears before the
  command's own output. To build it ahead of time: `docker compose --profile client build`.

The brief asks for "1 or more clients". The `client` service is part of the Compose topology, but it
runs on demand rather than as a long-running service, so `docker compose ps` shows the five
long-running services (1 controller and 4 workers). Every `docker compose run --rm client …` is one
more client, and the host's `curl` is another.

## Uploading and downloading objects

Upload any file with `multipart/form-data`: exactly one `file` part, and optionally one `metadata`
part with a flat JSON object of custom metadata.

```bash
head -c 2500000 /dev/urandom > report.pdf
curl -sS --form-string 'metadata={"project":"cc","version":1}' -F 'file=@report.pdf' \
  localhost:8080/api/v1/objects | tee upload.json | jq .
ID=$(jq -r .object_id upload.json)
```

The response (`201 Created`, with a `Location` header) has the object ID, name, size, content type
(from the part, else guessed from the name), SHA-256, chunk count, timestamps and custom metadata.

```bash
curl -sS -o downloaded.pdf localhost:8080/api/v1/objects/$ID   # the bytes
sha256sum report.pdf downloaded.pdf                            # macOS: shasum -a 256 …
curl -sS localhost:8080/api/v1/objects/$ID/metadata | jq .     # metadata, chunks and their workers
curl -sS -X PATCH -H 'Content-Type: application/json' -d '{"version":2,"reviewed":true}' \
  localhost:8080/api/v1/objects/$ID/metadata | jq .custom_metadata
curl -sS 'localhost:8080/api/v1/objects?limit=10&offset=0' | jq .  # list, oldest first
curl -sS -o /dev/null -w '%{http_code}\n' -X DELETE localhost:8080/api/v1/objects/$ID  # 204
```

- A download sends `Content-Type`, `Content-Length` and `X-Checksum-SHA256`. If a failure happens
  after the headers were sent, the connection is cut, so `curl` exits with code 18 and the body is
  shorter than `Content-Length`.
- PATCH adds or updates keys and never removes them; it never touches the data, so the checksum and
  chunks stay the same.
- Errors always have the body `{"error": "<code>", "message": "<text>"}`, for example
  `400 invalid_metadata`, `404 object_not_found`, `409 object_not_committed`, or
  `503 insufficient_workers` when fewer than 2 workers are ALIVE. Every code is listed in
  [docs/api.md](docs/api.md).

## Using the CLI

The `objstore` command runs in the `client` container and connects to the controller by itself.

```bash
docker compose run --rm client objstore --help
docker compose run --rm client objstore ls
docker compose run --rm client objstore stat <object_id>
docker compose run --rm client objstore status
```

To upload and download files from the host, mount the current directory into the container:

```bash
docker compose run --rm -v "$PWD:/work" -w /work client objstore put report.pdf --meta team=cc
docker compose run --rm -v "$PWD:/work" -w /work client objstore get <object_id> -o copy.pdf
```

| Command | What it does |
|---|---|
| `put FILE [--name N] [--content-type T] [--meta k=v]…` | Upload a file, streamed from disk; prints the new object's summary |
| `get ID [-o FILE]` | Download to a file (or stdout), then check the length and SHA-256; a file that fails either check is deleted |
| `ls [--limit N] [--offset N]` | List objects, oldest first |
| `stat ID` | Show the metadata, chunks and the workers holding each copy |
| `meta ID k=v…` | Add or update custom metadata keys |
| `rm ID` | Delete an object |
| `status` | Show worker states and replication health |
| `verify [--allow-under-replicated]` | Download and check every object, then require full replication |

`--url` (or the `CONTROLLER_URL` environment variable) points the CLI at another controller. It exits
with 0 on success, 1 on a failure (printing `objstore: <error code>: <message>`), and 2 on a usage
error.

## Web UI

Open **http://localhost:8080/** in a browser while the cluster is running. The page is served by the
controller and uses the same API as every other client.

- **Workers:** each worker's state (ALIVE, SUSPECT or DEAD), heartbeat age, files and healthy copies,
  refreshed every 2 seconds.
- **Replication:** healthy, under-replicated and unavailable chunks, pending deletes, and the chunks
  that need repair with where each copy is.
- **Objects:** upload a file with optional custom metadata, download, delete, and open an object's
  details: its chunks, the workers holding each copy, and its custom metadata, which you can edit.

It is a good companion to the failure demos: keep it open and run `docker stop worker2`, and the page
shows worker2 go SUSPECT, then DEAD, the affected chunks listed as under-replicated, and repair
bringing them back to 2 copies; `docker start worker2` shows reconciliation. A browser cannot stop a
container, so failures are still triggered from a terminal.

## Cluster status and replication

```bash
scripts/cluster-status.sh                                      # one-shot table of the workers
scripts/cluster-status.sh --watch --until worker2=DEAD         # refresh every second until…
curl -sS localhost:8080/internal/workers | jq .                # the same, as JSON
curl -sS localhost:8080/internal/replication | jq '{total_chunks, healthy, under_replicated, unavailable, pending_tombstones}'
curl -sS localhost:8080/internal/workers/worker1/chunks | jq '.chunks | length'                 # live, from the worker
curl -sS 'localhost:8080/internal/workers/worker1/chunks?view=expected' | jq '.chunks | length' # what the metadata says
```

A chunk counts as **healthy** with 2 HEALTHY copies on ALIVE workers, **under-replicated** with 1,
and **unavailable** with none. The cluster is fully replicated when both of the last two are 0.

## Running demos 1 to 6

Start the cluster, then run all six demos in order:

```bash
docker compose up -d --build --wait
make demo
```

Each demo prints what it is proving, uses only `curl`, `jq` and `docker`, creates its own objects,
and exits non-zero if any check fails, so they can also run one at a time:

| Script | Shows | Time |
|---|---|---|
| `scripts/demo-1-storage.sh` | Upload and download a file; the SHA-256 of the original and the download match | seconds |
| `scripts/demo-2-chunking.sh` | `5 × CHUNK_SIZE` plus a remainder becomes 6 ordered chunks; their sizes, IDs and workers | seconds |
| `scripts/demo-3-replication.sh` | Every chunk has 2 copies on different workers, each a real file in that worker's volume | seconds |
| `scripts/demo-4-metadata.sh` | Custom metadata at upload, read, PATCHed; checksum and chunks unchanged | seconds |
| `scripts/demo-5-failure.sh` | `docker stop worker2`: downloads still work, SUSPECT then DEAD, repair restores 2 copies; `docker start worker2`: reconciliation leaves exactly 2 copies per chunk | about 1 minute |
| `scripts/demo-6-integrity.sh` | A copy is corrupted inside a worker; the download is still correct, the logs name the chunk and worker, and repair restores 2 valid copies | seconds |

Demo 5 takes another worker as an argument (`scripts/demo-5-failure.sh worker3`).
`scripts/corrupt-chunk.sh WORKER [CHUNK_ID]` corrupts a copy by hand, and the worker's scrubber finds
it without any download (every 5 minutes, or sooner with `SCRUB_INTERVAL_SECS=10 docker compose up
-d worker1`).

## Configuration

Every setting is an environment variable with a default; the default `docker compose up` is the
brief's topology. `docker-compose.yml` passes `GC_GRACE_SECS` (controller) and `SCRUB_INTERVAL_SECS`
(workers) through from the host so a demo can shorten them; to change another setting, add it to the
service's `environment` in `docker-compose.yml`.

| Variable | Service | Default | Meaning |
|---|---|---|---|
| `HEARTBEAT_INTERVAL_MS` | both | `3000` | How often workers send a heartbeat |
| `SUSPECT_AFTER_MISSED` / `DEAD_AFTER_MISSED` | controller | `3` / `5` | Missed heartbeats before SUSPECT (9 s) and DEAD (15 s) |
| `REPAIR_CONCURRENCY` | controller | `4` | Chunks repaired at once |
| `GC_GRACE_SECS` | controller | `3600` | How long a file with no object must keep appearing before it is deleted |
| `STARTUP_GRACE_SECS` | controller | `120` | After a controller restart, how long reconciliation waits for every worker before deleting anything |
| `GC_DRY_RUN` | controller | `false` | Only log the orphan files that would be deleted |
| `SCRUB_INTERVAL_SECS` | worker | `300` | How often each worker re-hashes all its chunks |
| `JAVA_TOOL_OPTIONS` | all | empty | JVM options, for example `-Xmx128m` |

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `docker compose up` fails with "port is already allocated" | Something else uses port 8080. Stop it, or change the published port in `docker-compose.yml`. |
| A service stays `(health: starting)` or becomes `unhealthy` | Look at its log: `docker compose logs controller` (or `worker1` …). Docker needs about 1.5 GB of memory for the five JVMs. |
| Upload returns `503 insufficient_workers` | Fewer than 2 workers are ALIVE. After a start or restart they become ALIVE within a few seconds; check with `scripts/cluster-status.sh`. |
| Download returns `503 chunk_unavailable` | Every copy of some chunk is on a stopped worker. Start it again (`docker start worker2`); the chunk becomes available as soon as the worker is reconciled. |
| `curl` exits with code 18 on a download | The download was cut off after the headers, because every copy of a chunk failed mid-download. The controller log names the chunk; the object becomes downloadable again once repair has run. |
| `/internal/replication` shows under-replicated chunks | Normal while a worker is SUSPECT or DEAD: repair restores 2 copies within about 30 s if at least 2 workers are ALIVE. |
| After `docker compose up --build`, workers briefly show DEAD and chunks unavailable | Recreating the workers takes longer than 15 s, so the controller declared them DEAD. When they return, reconciliation verifies their copies and readmits them within seconds. |
| `pending_tombstones` stays above 0 | A delete is waiting for a stopped worker; it finishes once the worker is ALIVE again. |
| `./mvnw` fails at "Enforcer" | `JAVA_HOME` is not JDK 21. On macOS: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`. |
| A demo fails | It prints the check that failed. `docker compose logs controller --since 5m` shows what happened, as JSON with `operation`, `object_id`, `chunk_id` and `worker_id`. |
| You want to start from scratch | `docker compose down -v` (or `make reset`) deletes every volume and all stored objects. |

## Security scope and limitations

This is a course project for a single machine, not a production service:

- **No authentication, authorization or TLS.** Anyone who can reach port 8080 can read and delete
  every object. Keep it on a trusted machine.
- **One controller.** If it stops, the store is unavailable until it starts again (nothing is lost:
  its state is in SQLite on a volume, and recovery finishes interrupted uploads and deletes).
- **No rebalancing.** After a failure, new copies go to the remaining workers; a returning worker is
  not refilled, only used for new data (it is preferred, having the fewest copies).
- **Uploads are staged whole on the controller's disk** before chunking, so free space in the
  controller container limits the largest object. Memory stays at about one chunk per upload.
- **Replication factor 2** survives one failed copy per chunk at a time, not two at once.

## Documentation

- [docs/architecture.md](docs/architecture.md): design, flows, state machines, failure walks and
  crash recovery
- [docs/diagrams.md](docs/diagrams.md): system architecture and UML class, sequence, activity and
  state diagrams (Mermaid)
- [docs/api.md](docs/api.md): every endpoint, payload and error code
- [docs/roadmap.md](docs/roadmap.md): the plan for each phase
- [docs/decisions/](docs/decisions/): why each lasting decision was made
