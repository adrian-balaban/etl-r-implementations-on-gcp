#!/usr/bin/env bash
# Run ONE Flink variant on the oracle fixture and check it against spec/fixtures/expected.
#
#   bash harness/conformance.sh sql|datastream
#
# Needs postgres + redpanda from local-development-podman/podman-compose.yml (`make conformance`
# starts them). The jobs run in-process (the same as `make run-sql` / `make run-datastream`);
# this script adds only the run-scoped dump and the call to spec/conformance/compare.py.
set -euo pipefail
cd "$(dirname "$0")/.."

variant="${1:?usage: conformance.sh sql|datastream}"
case "$variant" in sql|datastream) ;; *) echo "variant must be sql or datastream" >&2; exit 2 ;; esac

RUN_ID="run-conf-${variant}-$(date +%Y%m%d%H%M%S)"
[[ "$RUN_ID" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "bad run id: $RUN_ID" >&2; exit 2; }
OUT="work/conformance/${variant}"
rm -rf "$OUT"; mkdir -p "$OUT"

echo "▶ flink ${variant} lane, run ${RUN_ID}"
make --no-print-directory "run-${variant}" RUN_ID="$RUN_ID"

# The ledger row is written by the recon stage (ReconService), not by the jobs, so the lane is
# not finished until recon has run. Balance-only (five args, no Kafka): settling against the
# confirmation stream is a separate guarantee, covered by SettleLoopIT / SqlLaneSettleIT.
# 55 = the data records in spec/fixtures/ACCOUNT.src (56 lines, one header).
echo "▶ recon (balance equation + run_ledger)"
mvn -q -pl recon -am -DskipTests package
java -jar recon/target/recon-service.jar "$RUN_ID" 55 "${JDBC_URL:-jdbc:postgresql://localhost:5432/mig}" mig mig

# table  schema  run-column — account_src carries the run id as _run_id, the rest as run_id.
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
  podman exec mig-postgres psql -U mig -d mig -v ON_ERROR_STOP=1 -c \
    "\\COPY (SELECT * FROM ${schema}.${table} WHERE ${col} = '${RUN_ID}') TO STDOUT WITH (FORMAT csv, DELIMITER E'\\t', HEADER)" \
    > "${OUT}/${table}.tsv"
done

# The DataStream variant routes records in one pass and has no intermediate layers, so it is held
# to the single-pass profile (spec/conformance/compare.py states exactly what that skips).
profile=staged; [[ "$variant" == datastream ]] && profile=single-pass
echo "▶ comparing against spec/fixtures/expected (profile: ${profile})"
python3 -I ../../spec/conformance/compare.py "$OUT" --profile "$profile"
