#!/bin/bash
# source-seeder (spec "Seeding the source") — one Cloud Run Job execution per action, inside the VPC.
#
#   seed.sh init                          etlr_reader + etlr_source on the source; the lane's schema on the lane
#   seed.sh fixture  <run_id> [ready_at]  spec/fixtures/ACCOUNT.src, vouched for under run_id (ready_at defaults to
#                                         2026-09-07T00:00:00Z, the oracle's as-of date)
#   seed.sh generate <run_id> <accounts>  harness/generate.py --accounts N, vouched for under run_id
#   seed.sh tamper                        ACC000000011's balance + 1, after its control row (the negative path)
#   seed.sh dump     <run_id>             the seven lane tables -> $DUMP_DIR/<run_id>/tables/, source_read beside them
#   seed.sh count    <run_id>             rows per lane table for run_id (the "zero rows written" check)
#
# It plays the team that owns the Source System (fixture, generate, tamper) and the operator's hands inside the VPC
# (init, dump, count): both databases have private IPs only. Passwords arrive as environment variables (Cloud Run
# secret env from Secret Manager), never as arguments.
set -euo pipefail

: "${SOURCE_HOST:?}"
: "${LANE_HOST:?}"
SOURCE_PORT="${SOURCE_PORT:-5432}"
LANE_PORT="${LANE_PORT:-5432}"
SOURCE_DB="${SOURCE_DB:-source}"
SOURCE_ADMIN_USER="${SOURCE_ADMIN_USER:-mig}"
LANE_DB="${LANE_DB:-mig}"
LANE_USER="${LANE_USER:-mig}"
SEED_ROOT="${SEED_ROOT:-/seed}"
DUMP_DIR="${DUMP_DIR:-/mnt/gcs/evidence}"
WORK_TMP="${WORK_TMP:-/tmp}"
FIXTURE_READY_AT="2026-09-07T00:00:00Z"
TAMPER_KEY="ACC000000011"
LANE_TABLES="account_src:etlr_extraction:_run_id account_curated:etlr_transformation:run_id account_enriched:etlr_transformation:run_id account_target:etlr_transformation:run_id reject_log:etlr_recon:run_id record_lineage:etlr_recon:run_id run_ledger:etlr_recon:run_id"

src_psql() {
  PGPASSWORD="${SOURCE_ADMIN_PASSWORD:?}" psql -h "$SOURCE_HOST" -p "$SOURCE_PORT" -U "$SOURCE_ADMIN_USER" \
    -d "$SOURCE_DB" -v ON_ERROR_STOP=1 -q "$@"
}
lane_psql() {
  PGPASSWORD="${LANE_PASSWORD:?}" psql -h "$LANE_HOST" -p "$LANE_PORT" -U "$LANE_USER" -d "$LANE_DB" \
    -v ON_ERROR_STOP=1 -q "$@"
}
usage() { grep '^#   seed.sh' "$0" | sed 's/^#   //' >&2; exit 2; }
need_run_id() {
  if ! [[ "${1:-}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || [ "${#1}" -gt 38 ]; then
    echo "seed: '${1:-}' is not a usable run id (lowercase letters, digits, '-', at most 38 characters)" >&2
    exit 2
  fi
}
copy_out() { # schema table column run_id
  lane_psql -c "\\COPY (SELECT * FROM $1.$2 WHERE $3 = '$4') TO STDOUT WITH (FORMAT csv, DELIMITER E'\\t', HEADER)"
}

mode="${1:-}"
[ $# -gt 0 ] && shift
case "$mode" in
  init)
    : "${READER_PASSWORD:?}"
    # sql/source-init.sql hard-codes the local password 'reader'; here the role gets the Secret Manager value,
    # read inside psql (\getenv, psql >= 15) so it is in no argv. Same grants as sql/source-init.sql, idempotent.
    src_psql <<'SQL'
\getenv reader_password READER_PASSWORD
SELECT format('CREATE ROLE etlr_reader LOGIN PASSWORD %L', :'reader_password')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'etlr_reader') \gexec
SELECT format('ALTER ROLE etlr_reader LOGIN PASSWORD %L', :'reader_password') \gexec
CREATE SCHEMA IF NOT EXISTS etlr_source;
GRANT USAGE ON SCHEMA etlr_source TO etlr_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA etlr_source GRANT SELECT ON TABLES TO etlr_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA etlr_source TO etlr_reader;
SQL
    lane_psql -f "$SEED_ROOT/sql/postgres-init.sql" < /dev/null
    lane_psql -f "$SEED_ROOT/sql/postgres-init-jdbc.sql" < /dev/null
    echo "seed: source (etlr_reader, etlr_source) and lane schemas initialised" ;;
  fixture)
    need_run_id "${1:-}"
    python3 -I "$SEED_ROOT/harness/load_db.py" "$SEED_ROOT/fixtures/ACCOUNT.src" \
      --run-id "$1" --ready-at "${2:-$FIXTURE_READY_AT}" | src_psql
    echo "seed: fixture loaded and vouched for under $1" ;;
  generate)
    need_run_id "${1:-}"
    [[ "${2:-}" =~ ^[1-9][0-9]*$ ]] || usage
    rm -rf "$WORK_TMP/generated"
    python3 -I "$SEED_ROOT/harness/generate.py" --accounts "$2" --out "$WORK_TMP/generated"
    python3 -I "$SEED_ROOT/harness/load_db.py" "$WORK_TMP/generated/ACCOUNT.src" --run-id "$1" | src_psql
    echo "seed: $2 generated accounts loaded and vouched for under $1" ;;
  tamper)
    n="$(src_psql -At -c "WITH u AS (UPDATE etlr_source.account SET balance = balance + 1 WHERE acct_id = '${TAMPER_KEY}' RETURNING 1) SELECT count(*) FROM u" < /dev/null)"
    if [ "$n" != 1 ]; then
      echo "seed: ${TAMPER_KEY} not found in etlr_source.account — load the fixture first" >&2
      exit 1
    fi
    echo "seed: ${TAMPER_KEY}'s balance changed after its control row" ;;
  dump)
    need_run_id "${1:-}"
    out="$DUMP_DIR/$1"
    mkdir -p "$out/tables"
    for spec in $LANE_TABLES; do
      IFS=: read -r table schema col <<< "$spec"
      copy_out "$schema" "$table" "$col" "$1" < /dev/null > "$out/tables/${table}.tsv"
    done
    copy_out etlr_recon source_read run_id "$1" < /dev/null > "$out/source_read.tsv"
    echo "seed: dumped $1 to $out" ;;
  count)
    need_run_id "${1:-}"
    for spec in $LANE_TABLES source_read:etlr_recon:run_id; do
      IFS=: read -r table schema col <<< "$spec"
      echo "${table}=$(lane_psql -At -c "SELECT count(*) FROM ${schema}.${table} WHERE ${col} = '$1'" < /dev/null)"
    done ;;
  *)
    usage ;;
esac
