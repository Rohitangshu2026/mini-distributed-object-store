---
status: accepted (pending instructor confirmation)
---

# Chunk size is 1,000,000 bytes

The brief says objects are split into "1 MB" chunks. We read that literally as the SI megabyte, `CHUNK_SIZE = 1_000_000` bytes, because `CLAUDE.md` says the brief wins whenever there is a conflict, and "MB" in SI means 10^6 bytes. The constant is defined once, as `Chunking.CHUNK_SIZE` in the `common` module; every test derives its sizes from it, and `scripts/lib.sh` mirrors it for the demos.

## Considered options

- **1,000,000 bytes (chosen).** This is the literal reading of the brief.
- **1 MiB (1,048,576 bytes).** This is common in storage systems and aligns with page sizes, but it reads "MB" as "MiB", which the brief does not say.

## Consequences

- Switching to 1 MiB is a one-line change to `CHUNK_SIZE`. Nothing else names a byte count. Demo 2 uploads `5 × CHUNK_SIZE` plus a remainder, so it shows multiple chunks under either reading.
- Chunks written with one size stay valid after a switch, because each object records its own chunk sizes. Mixed sizes across objects are harmless.

## Instructor's answer

Open. Ask whether 1,000,000-byte chunks are fine or 1 MiB is preferred, and record the answer here.
