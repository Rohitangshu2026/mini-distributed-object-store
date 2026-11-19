---
status: accepted (2026-11-19)
---

# Worker chunk files carry their expected checksum in the name

A worker must verify every chunk against its expected checksum on every read, so it has to store that checksum somewhere. Each chunk is stored as one file:

```
${DATA_DIR}/chunks/<aa>/<bb>/<chunk_id>.<sha256>
```

- **Checksum in the name.** One atomic rename publishes the bytes and their expected checksum together. The index is rebuilt at boot by listing directories, without reading every file. Running `sha256sum` on a file and comparing it with the name is direct evidence in the demos.
- **The name is never proof.** The worker re-hashes the contents on every GET, every scrub and every verification read. The controller checks again against its own metadata, which is authoritative. A corrupt file renamed to match its contents would still fail the controller's check.
- **Folders come from a hash.** `<aa>/<bb>` are the first 4 hex characters of SHA-256(`chunk_id`). Chunk IDs begin with a UUID v7 timestamp, so their own prefix would put nearly every file in one directory.
- **Write protocol.** Temp file in the target directory → `FileChannel.force(true)` → `Files.move(…, ATOMIC_MOVE)` → `force` on the directory. A crash leaves either the old state or the new state, plus at most a `*.tmp` file that boot deletes.
- **Quarantine, not deletion.** A file that fails verification is renamed `<chunk_id>.<sha256>.corrupt`. It is removed only when that chunk is rewritten with a verified copy, deleted through a tombstone, or cleaned up as an orphan.
- **Locking.** There is at most one servable file per chunk ID. Write, delete and verify-then-quarantine take a lock from 64 stripes by chunk ID, so a scrub can never quarantine a file that a repair has just replaced.

## Considered options

- **A sidecar `.sha256` file.** Two files mean two renames, and a crash between them leaves an inconsistent pair to clean up.
- **A header inside the chunk file.** The file would no longer be the raw chunk, so `sha256sum` on it would not match the metadata in the demos.
- **Extended attributes.** Their durability guarantees vary by filesystem and are harder to explain.
- **Hashing every file at boot to rebuild the index.** Slow, and it would trust whatever the disk now contains.
