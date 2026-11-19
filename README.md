# mini-distributed-object-store

A small distributed object store, built for a Cloud Computing (Infrastructure) course. One
controller and four storage workers run under Docker Compose. Objects are split into 1 MB chunks,
and every chunk is stored on two different workers, so the store keeps serving data when a worker
fails and repairs itself afterwards.

**Status:** work in progress. The cluster starts and reports its health; storing objects arrives in
later phases of [the roadmap](docs/roadmap.md).

## Prerequisites

- Docker with Compose v2 or later, with about 1.5 GB of memory for the five JVMs.
- To build and test outside Docker: JDK 21 as `JAVA_HOME`. The build stops on any other version.
  The Maven Wrapper (`./mvnw`) downloads Maven itself.
- Optional on the host: `make`, `curl` and `jq`.

## Build and test

```bash
./mvnw -B verify
```

This checks formatting (google-java-format), compiles with `-Xlint:all -Werror` and Error Prone,
and runs the unit, property and integration tests. `./mvnw spotless:apply` fixes formatting.

## Start and stop the cluster

```bash
docker compose up -d --build   # or: make up, which also waits until every service is healthy
docker compose ps              # controller and worker1 to worker4, all healthy
docker compose down            # stop; stored data stays in the volumes
docker compose down -v         # stop and delete all stored data (make reset)
```

Only the controller is reachable from the host, on port 8080. Each worker keeps its data in its own
named volume (`worker1-data` to `worker4-data`). Nothing restarts by itself, so `docker stop worker2`
behaves like a failed worker until `docker start worker2`.

## Check health

```bash
curl -fsS localhost:8080/health
docker compose run --rm client curl -fsS http://worker1:9000/health
```

The workers are only on the Compose network, so their health is checked from the client container.
Each worker reports its `boot_count`, which goes up by one on every restart.

## How clients connect

Clients reach the store only through the controller's REST API on port 8080:

- `curl` on the host, against `http://localhost:8080`;
- the `client` container (Compose profile `client`), which has the `objstore` CLI, `curl` and `jq`:
  `docker compose run --rm client objstore --help`.

## Coming in later phases

| Section | Phase |
|---|---|
| Uploading and downloading objects | 5 |
| Using the CLI | 6 |
| Running demos 1 to 6 | 5 to 9 |
| Troubleshooting | 10 |

## Documentation

- [docs/architecture.md](docs/architecture.md): design, flows, state machines and failure handling
- [docs/api.md](docs/api.md): every endpoint, payload and error code
- [docs/roadmap.md](docs/roadmap.md): the plan for each phase
- [docs/decisions/](docs/decisions/): why each lasting decision was made
