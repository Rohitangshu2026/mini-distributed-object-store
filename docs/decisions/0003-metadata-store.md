---
status: accepted (2026-11-19)
---

# SQLite with JdbcClient and Flyway, no JPA

The controller's metadata is five small tables whose important queries are naturally SQL. One example: "chunks of committed objects with fewer than 2 HEALTHY copies on ALIVE workers". We keep SQLite as the authoritative store, access it with Spring `JdbcClient`, put every multi-statement change in an explicit `TransactionTemplate` transaction, and manage the schema with Flyway.

## Settings

| Setting | Value | Why |
|---|---|---|
| `journal_mode` | `WAL` | Readers do not block the writer |
| `synchronous` | `FULL` | A committed upload survives power loss (I1) |
| `foreign_keys` | `true` | SQLite enforces foreign keys only when each connection asks |
| `busy_timeout` | `5000` | Writers queue for the write lock instead of failing at once |
| `transaction_mode` | `IMMEDIATE` | Every explicit transaction takes the write lock at `BEGIN`, so read-then-write transactions cannot deadlock |
| HikariCP pool | 4 connections | |
| Tables | `STRICT` | Column types are enforced |
| Timestamps | Epoch milliseconds (`INTEGER`) | They compare correctly as numbers; RFC 3339 text compares wrongly when the number of fraction digits varies. The API still returns RFC 3339 UTC |

Because every explicit transaction takes the write lock, reads run as single autocommit statements, and views that need several tables use one query with joins. Transactions never span network or file I/O (A5).

## Considered options

- **Spring Data JPA and Hibernate.** It hides the SQL that the safety rules depend on (for example, the N5 count immediately before a delete). It adds session and flush behaviour to explain, and Hibernate's SQLite dialect is community-maintained. Not worth it for five tables.
- **`@Transactional`.** It works, but calls within the same class silently skip the proxy. `TransactionTemplate` keeps each boundary visible in the code.
- **Another database (PostgreSQL).** It would add a sixth long-running container to the brief's topology for no requirement. SQLite on the controller's volume is enough for one controller.

## Consequences

- There is no compile-time query checking (the Rust plan had sqlx for that). Instead, every repository method has an integration test against a real SQLite file.
- SQLite allows one writer at a time. Transactions are kept to a few statements, and `busy_timeout` absorbs contention. A `SQLITE_BUSY` after 5 s is a 500 for a request, or a logged retry for a background job.
- Schema changes are new migration files only; an applied migration is never edited.
