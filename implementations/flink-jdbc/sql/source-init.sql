-- The Source System database, before any load: the schema and the read-only role the lane connects as.
-- Tables are created later by spec/harness/load_db.py (as the owner); default privileges grant them.
CREATE ROLE etlr_reader LOGIN PASSWORD 'reader';
CREATE SCHEMA IF NOT EXISTS etlr_source;
GRANT USAGE ON SCHEMA etlr_source TO etlr_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA etlr_source GRANT SELECT ON TABLES TO etlr_reader;
