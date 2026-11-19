---
status: accepted (2026-11-19)
---

# Java 21 and Spring Boot 4.1 on platform threads

The project was first planned in Rust. Before any code was written it moved to Java, so the implementation language matches what Rohit wants to build and defend. The engineering plan was kept; only the language-specific parts changed.

**Decision.**

- **Language:** Java 21 (Eclipse Temurin).
- **Framework:** Spring Boot 4.1.2, with Spring MVC on Tomcat for both the controller and the workers.
- **Concurrency:** platform threads in fixed-size pools.
- **CLI:** a plain-Java picocli application.

## Versions

Checked against Maven Central and the Spring Boot 4.1.2 dependency list on 2026-11-19.

| Component | Version | Source |
|---|---|---|
| Java (Temurin) | 21 | Supported until 2029-12-31 |
| Spring Boot | 4.1.2 | Released 2026-10-22; 4.1 line supported until 2027-07-31; Java 17–26 |
| Spring Framework | 7.0.10 | Spring Boot BOM |
| Tomcat | 11.0.27 | Spring Boot BOM |
| Jackson | 3.1.7 (`tools.jackson`) | Spring Boot BOM |
| HikariCP | 7.0.2 | Spring Boot BOM |
| sqlite-jdbc | 3.53.4.0 | Spring Boot BOM |
| Flyway | 12.4.0 (SQLite support in `flyway-core`) | Spring Boot BOM |
| JUnit | 6.0.3 | Spring Boot BOM |
| Mockito / AssertJ | 5.23.0 / 3.27.7 | Spring Boot BOM |
| Logback / SLF4J | 1.5.38 / 2.0.20 | Spring Boot BOM |
| picocli | 4.7.7 | Pinned in the root POM |
| jqwik | 1.10.2 | Pinned in the root POM |
| Error Prone | 2.53.0 | Pinned in the root POM |
| Spotless Maven plugin | 3.11.0 | Pinned in the root POM |
| Maven Enforcer plugin | 3.7.0 | Pinned in the root POM |
| Maven | 3.9.11 | Maven Wrapper |

The root `pom.xml` becomes the source of truth for versions from Phase 0. Patch releases within 4.1.x may be taken when `./mvnw -B verify` passes. A move to 4.2 or later needs Rohit's approval.

## Considered options

- **Java 25 instead of 21.** Its one gain for this project is that virtual threads no longer pin their carrier inside `synchronized` blocks (JEP 491, JDK 24). With platform threads that gain does not apply. Java 21 is the more conservative baseline.
- **Virtual threads instead of platform pools.** The system's concurrency is tiny: 4 workers, a few clients, 2 copy writes per chunk, and 4 repairs. The SQLite driver uses native calls, which pin virtual threads on every Java version. Fixed pools are simpler to reason about and to bound (A3).
- **Spring WebFlux instead of MVC.** Reactive code is harder to read and defend line by line, and JDBC is blocking anyway. MVC with blocking I/O streams chunk by chunk with bounded memory.
- **Spring Boot 3.5, 4.0 or 4.2.** 3.5 lost free support on 2026-06-30, and 4.0 loses it on 2026-12-31. 4.2.0 was at release candidate 2 when this was written.
- **Layered Spring Boot jars in images.** Not needed at this scale; plain fat jars keep the Dockerfile short.
- **Spring Boot for the CLI.** Its startup time is too slow for a command-line tool, so the CLI is plain Java with picocli and the JDK `HttpClient`.

## Phase 0 gates and fallbacks

- **jqwik on JUnit 6.** jqwik is in maintenance mode and built against JUnit Platform 1.14.4; Spring Boot 4.1 brings JUnit 6.0.3. If a jqwik property cannot run under the Spring Boot-managed platform, or cannot shrink a failing case, property tests become JUnit `@ParameterizedTest` tests with explicit boundary cases plus a seeded random generator. No other dependency is added.
- **Error Prone on JDK 21.** If it cannot run cleanly with the flags in `.mvn/jvm.config`, the build keeps `-Xlint:all -Werror` and drops Error Prone.

## What changed from the Rust plan

| Rust plan | Java plan |
|---|---|
| Cargo workspace | Maven multi-module project |
| tokio | Spring MVC on Tomcat, fixed-size pools |
| axum | Spring MVC |
| reqwest | `RestClient` |
| serde | Jackson |
| sqlx | `JdbcClient`, `TransactionTemplate`, Flyway |
| clap | picocli |
| proptest | jqwik |
| `thiserror` | `ErrorCode`, `ApiException`, `ErrorBody` in `common` |
| `cargo fmt` | Spotless |
| clippy | `-Xlint:all -Werror` and Error Prone |
| cargo-chef | A BuildKit cache mount for `~/.m2` |
| `debian:trixie-slim` | `eclipse-temurin:21-jre` |
| `healthcheck` subcommand | `curl` (a subcommand would start a JVM every interval) |
| `.sqlx` offline query cache | Integration tests against real SQLite for every query |
| `ChunkStore` and `Clock` traits | A concrete `ChunkStore` tested on real temp directories, and `java.time.Clock`; `WorkerClient` stays an interface |
| Streaming multipart parser | Servlet multipart staging (decision 0004) |
| `CONTROLLER_BIND`, `WORKER_BIND`, `DATABASE_URL` as a sqlx URL | `SERVER_PORT`, and `DATABASE_URL` as a JDBC URL |

Fixes made during the migration that apply to any language:

- Orphan cleanup moved into the mandatory phases.
- The metadata store is built before membership.
- The worker's checksum lives in the file name, and the folder layout uses a hash.
- Reconciliation also runs on any DEAD → ALIVE change.
- Crash recovery uses recorded intent (decision 0006).
- One database-driven repair loop.
- Timestamps are stored as epoch milliseconds.
- Phases went from 9 to 11.

## What was preserved

R1–R12 and I1–I5, all client and internal API contracts and status codes, the data model and its status values, the chunk ID format and chunk size, placement and membership timings, the reconciliation safety rules, the Compose topology and environment defaults, the six demos, the extension order, and the "Claude builds, Rohit reviews" working agreement.
