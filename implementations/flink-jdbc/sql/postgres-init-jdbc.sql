-- flink-jdbc's only addition to ../flink/sql/postgres-init.sql: what intake measured of the source
-- (spec/PIPELINE.md, "Source System as a database"). recon-jdbc compares it with the control row.
CREATE TABLE IF NOT EXISTS etlr_recon.source_read (
  run_id         TEXT PRIMARY KEY,
  row_count      BIGINT  NOT NULL,
  content_digest NUMERIC NOT NULL);
