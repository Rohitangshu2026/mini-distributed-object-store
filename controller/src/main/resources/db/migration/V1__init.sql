-- The controller's metadata schema (docs/architecture.md §7). Applied migrations are never
-- edited: a schema change is a new migration file.

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
