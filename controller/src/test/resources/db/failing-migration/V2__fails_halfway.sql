-- Test only: creates a table and then fails, so FailedMigrationIT can check that nothing of this
-- migration survives.
CREATE TABLE half_done (id TEXT PRIMARY KEY) STRICT;
INSERT INTO no_such_table VALUES (1);
