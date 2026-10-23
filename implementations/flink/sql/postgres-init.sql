-- postgres-init.sql — replaces BigQuery.
--
-- This DDL is generated from contracts/ where possible, so that
-- the schema and the contracts cannot drift apart in silence. Where that is possible, it now
-- is: etlr_extraction.account_src below is checked, on every `mvn test`, against
-- mig.common.GeneratePostgresDdl's output from contracts/tds/tds-src-project1.def +
-- contracts/artefacts.json (GeneratePostgresDdlTest). Rename a TDS field without touching
-- this file and the build fails.
--
-- The etlr_transformation and etlr_recon tables below remain hand-written, deliberately: their
-- columns come from three places at once — TDS fields, mapping-yaml aliases, and enrichment
-- columns declared in no contract at all (balance_band, account_age_years, and the branch_*
-- columns joined from contracts/reference/branches.json). Generating those would mean typing
-- the same column list into a Java method, which is the same hand-written DDL wearing a
-- disguise. They are verified instead by fixtures/expected/*.tsv, column for column.

CREATE SCHEMA IF NOT EXISTS etlr_extraction;
CREATE SCHEMA IF NOT EXISTS etlr_transformation;
CREATE SCHEMA IF NOT EXISTS etlr_recon;

-- ───────────────────────────────────────────────────────── etlr_extraction

-- Mirrors contracts/tds/tds-src-project1.def (ACCOUNT record) + the ingest metadata
-- columns from contracts/artefacts.json `columns.src`.
CREATE TABLE etlr_extraction.account_src (
  acct_id       TEXT NOT NULL,
  cust_id       TEXT NOT NULL,
  client_type   TEXT NOT NULL,
  product_code  TEXT NOT NULL,
  currency      TEXT NOT NULL,
  open_date     DATE NOT NULL,
  status        TEXT NOT NULL,
  balance       TEXT NOT NULL,  -- decimal carried as string (schema.py: decimals -> STRING)
  _run_id       TEXT NOT NULL,
  _batch_id     BIGINT,
  _source_file  TEXT,
  _source_key   TEXT,
  _account_key  TEXT,
  _ingested_at  TEXT
);

-- ───────────────────────────────────────────────────── etlr_transformation

-- Job 2 output, before the reference-data join.
CREATE TABLE etlr_transformation.account_curated (
  account_id        TEXT NOT NULL,
  customer_id       TEXT NOT NULL,
  product_code      TEXT NOT NULL,
  currency          TEXT NOT NULL,
  opened_on         DATE NOT NULL,
  status_code       TEXT NOT NULL,
  balance_minor     BIGINT NOT NULL,
  balance_amount    TEXT NOT NULL,
  segment           TEXT,
  branch            TEXT,
  risk_rating       TEXT,
  balance_band      TEXT NOT NULL,
  account_age_years INTEGER NOT NULL,
  run_id            TEXT NOT NULL,
  account_key       TEXT NOT NULL
);

-- account_curated + the branches.json join.
CREATE TABLE etlr_transformation.account_enriched (
  account_id        TEXT NOT NULL,
  customer_id       TEXT NOT NULL,
  product_code      TEXT NOT NULL,
  currency          TEXT NOT NULL,
  opened_on         DATE NOT NULL,
  status_code       TEXT NOT NULL,
  balance_minor     BIGINT NOT NULL,
  balance_amount    TEXT NOT NULL,
  segment           TEXT,
  branch            TEXT,
  risk_rating       TEXT,
  balance_band      TEXT NOT NULL,
  account_age_years INTEGER NOT NULL,
  branch_name       TEXT,
  branch_city       TEXT,
  branch_region     TEXT,
  run_id            TEXT NOT NULL,
  account_key       TEXT NOT NULL
);

-- Job 3 output.
CREATE TABLE etlr_transformation.account_target (
  run_id      TEXT NOT NULL,
  account_id  TEXT NOT NULL,
  account_key TEXT NOT NULL,
  doc_json    TEXT NOT NULL
);

-- ─────────────────────────────────────────────────────────────── etlr_recon

-- reject_log.source_key is EMPTY STRING for parse-stage rejects, not
-- `raw:<hash>` — do not "fix" this to match record_lineage's rule.
CREATE TABLE etlr_recon.reject_log (
  run_id      TEXT NOT NULL,
  batch_id    BIGINT NOT NULL,
  source_key  TEXT NOT NULL DEFAULT '',
  stage       TEXT NOT NULL,
  reason      TEXT NOT NULL,
  detail      TEXT NOT NULL,
  raw_record  TEXT NOT NULL
);

-- record_lineage.source_key IS `raw:<hash>` for parse-stage rejects.
CREATE TABLE etlr_recon.record_lineage (
  run_id       TEXT NOT NULL,
  source_key   TEXT NOT NULL,
  account_key  TEXT,
  door         TEXT NOT NULL,
  stage        TEXT NOT NULL,
  reason       TEXT NOT NULL,
  detail       TEXT NOT NULL,
  source_file  TEXT
);

-- Closes the equation per stage: the intake job's own row, then the
-- full lane closes 55 = 50 + 5.
CREATE TABLE etlr_recon.run_ledger (
  run_id              TEXT NOT NULL,
  src_read            BIGINT NOT NULL,
  extraction_written  BIGINT NOT NULL,
  rejected            BIGINT NOT NULL,
  balanced            BOOLEAN NOT NULL,
  created_at          DOUBLE PRECISION NOT NULL
);
