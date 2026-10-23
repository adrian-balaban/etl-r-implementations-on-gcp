#!/usr/bin/env bash
# Run the flink-jdbc lane on the oracle fixture loaded into the typed source table, and check it against the
# typed-source oracle (spec/fixtures/db/). Needs the containers `make conformance` starts. The jobs run in-process.
set -euo pipefail
cd "$(dirname "$0")/.."

RUN_ID="run-conf-jdbc-$(date +%Y%m%d%H%M%S)"
OUT="work/conformance/jdbc"
rm -rf "$OUT"; mkdir -p "$OUT"

echo "▶ flink-jdbc lane, run ${RUN_ID}"
make --no-print-directory run RUN_ID="$RUN_ID"

echo "▶ dumping the seven tables for ${RUN_ID} -> ${OUT}"
for spec in \
  "account_src etlr_extraction _run_id" \
  "account_curated etlr_transformation run_id" \
  "account_enriched etlr_transformation run_id" \
  "account_target etlr_transformation run_id" \
  "reject_log etlr_recon run_id" \
  "record_lineage etlr_recon run_id" \
  "run_ledger etlr_recon run_id"; do
  read -r table schema col <<<"$spec"
  podman exec etlr-jdbc-lane psql -U mig -d mig -v ON_ERROR_STOP=1 -c \
    "\\COPY (SELECT * FROM ${schema}.${table} WHERE ${col} = '${RUN_ID}') TO STDOUT WITH (FORMAT csv, DELIMITER E'\\t', HEADER)" \
    > "${OUT}/${table}.tsv"
done

echo "▶ comparing against spec/fixtures/db/expected (profile: staged)"
python3 -I ../../spec/conformance/compare.py "$OUT" \
  --expected ../../spec/fixtures/db/expected --manifest ../../spec/fixtures/db/manifest.json
