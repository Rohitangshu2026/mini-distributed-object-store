---
status: accepted (2026-11-19)
---

# Crash safety through recorded intent, safe repeats and tombstones

A SQLite transaction cannot make a file operation on another machine atomic, so the design does not try. The controller follows four rules:

1. **Record the intent first.** Before any write or delete on a worker, a committed transaction records it: a PENDING replica row, the DELETING status, or a tombstone (N4).
2. **Make every worker operation safe to repeat.** A chunk PUT is an atomic rename of verified bytes; DELETE succeeds whether or not the file exists; GET changes nothing except quarantining a corrupt file.
3. **Record completion only after the worker confirms.** A failed call, or one whose outcome is unknown, turns its PENDING row into a tombstone, because the worker may still hold the bytes.
4. **Recovery replays recorded intent,** at startup and every 10 s. UPLOADING or DELETING objects that no request in this process owns are finished. At startup, PENDING rows of committed objects become tombstones.

So every chunk file on every worker is traceable to a database row, either a replica or a tombstone. Orphan cleanup is a safety net for files the database never knew, such as files planted by hand or left over after the metadata volume was lost.

## Tombstones are the only route to deleting a copy

This includes object deletes, upload aborts, failed writes, extra copies found at rejoin, untracked copies and orphans.

- **Deleting an object writes tombstones first.** D1 marks the object DELETING and turns every replica row into a tombstone in one transaction. D2 sends the deletes and removes each tombstone once its worker confirms. D3 removes the metadata.
- **Aborting an upload** moves the object from UPLOADING to DELETING the same way. That transition is added; no status value is new.
- **The delete decision lives in one place.** `TombstoneJob` sends deletes only to ALIVE workers, using the tombstone table in `docs/architecture.md` §8.
- **N5 is enforced there.** A copy of a live chunk is deleted only if at least 2 HEALTHY copies on ALIVE workers will remain.
- **Otherwise the copy is adopted.** It is verified; if valid it becomes a HEALTHY replica, and if not, the tombstone is dropped. This keeps a chunk from being stuck at one copy when only two workers are alive.
- **No pending delete can race a fresh copy.** Placement never chooses a worker that holds a replica row or a tombstone for that chunk.

## Orphan cleanup is mandatory

Invariant I5 requires that no orphan outlives the GC grace period. The original plan parked garbage collection as extension X1. Weakening I5 was not acceptable, so orphan cleanup moved into Phase 8 and X1 was retired.

- A file whose object does not exist becomes a tombstone only after two complete inventories at least `GC_GRACE_SECS` apart have both reported it.
- Orphan tombstones are created only after the startup gate has passed.
- `GC_DRY_RUN` reports instead of acting.

## Considered options

- **Delete first, then record the failure** (the original D2). It is safe, because the DELETING status was the durable intent. But it spread delete calls across several code paths, and the N5 exception would not literally be "covered by durable tombstones".
- **Two-phase commit across workers.** Far heavier than this system needs, and still not atomic once a worker crashes.

## Consequences

- **A lost 201 means a duplicate.** Uploads are not idempotent: a client that retries after a lost 201 creates a second object.
- **A retried DELETE may see 409 first.** A client that retries a DELETE whose response was lost sees 409 while recovery finishes the delete, then 404.
- **The N5 check cannot be atomic with the delete.** The check commits before the DELETE request is sent. If another worker fails in between, the chunk drops to one copy and repair restores it. It can reach zero copies only if both other copies fail at once, which replication factor 2 does not promise to survive.
- **Every crash point has a test.** Each row of the crash tables in `docs/architecture.md` §10.4 has a recovery test.
