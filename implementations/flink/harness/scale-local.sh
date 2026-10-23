#!/bin/bash
# Local scale run of the SQL lane. Free: a podman Postgres and the built jars,
# no GCP. Usage:  harness/scale-local.sh <accounts> [results-file]
#
# The generator seeds exactly 5 defects at any size, so for N accounts the lane must end with
#   written=N  rejected=5  src_read=N+5  balanced=true   and   ceil(N/200) batches (recon's check).
# Anything else is a defect at that size, not noise.
#
# Prerequisite: `mvn -DskipTests package` (the shaded jars under */target/).
set -euo pipefail
N="${1:?usage: scale-local.sh <accounts> [results-file]}"
RESULTS="${2:-/dev/stdout}"
cd "$(dirname "$0")/.."

PORT="${SCALE_PG_PORT:-15461}"
NAME="mig-scale-pg-$N"
RUN="run-scale-$N"
WORK="$(mktemp -d /tmp/scale-work.XXXX)"
SQLJAR=variant-flink-sql-batch-job/target/variant-flink-sql-batch-job.jar
RECONJAR=recon/target/recon-service.jar
JDBC="jdbc:postgresql://localhost:${PORT}/mig"

cleanup() { podman rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

python3 -m harness.generate --accounts "$N" --out "$WORK/src" > "$WORK/gen.log" 2>&1
SRC="$WORK/src/ACCOUNT.src"
DATA_RECORDS=$(( $(wc -l < "$SRC") - 1 ))   # minus the header row (see Gate.dataRecords)

podman rm -f "$NAME" >/dev/null 2>&1 || true
podman run --rm -d --name "$NAME" -e POSTGRES_USER=mig -e POSTGRES_PASSWORD=mig -e POSTGRES_DB=mig \
  -p "${PORT}:5432" docker.io/library/postgres:16 >/dev/null
for _ in $(seq 1 40); do podman exec "$NAME" pg_isready -U mig -d mig >/dev/null 2>&1 && break; sleep 1; done
podman cp sql/postgres-init.sql "$NAME:/tmp/init.sql"
podman exec "$NAME" psql -q -U mig -d mig -f /tmp/init.sql >/dev/null

t() { date +%s.%N; }
elapsed() { python3 -c "print(f'{$2 - $1:.1f}')"; }

t0=$(t)
java -cp "$SQLJAR" mig.sql.IntakeJob "$SRC" "$RUN" "$JDBC" mig mig . > "$WORK/intake.log" 2>&1
t1=$(t)
java -cp "$SQLJAR" mig.sql.CurateEnrichJob "$RUN" 2026-09-07 "$JDBC" mig mig . > "$WORK/curate.log" 2>&1
t2=$(t)
java -cp "$SQLJAR" mig.sql.ProduceJob "$RUN" "$JDBC" mig mig > "$WORK/produce.log" 2>&1
t3=$(t)
set +e
java -jar "$RECONJAR" "$RUN" "$DATA_RECORDS" "$JDBC" mig mig > "$WORK/recon.log" 2>&1
recon_exit=$?
set -e
t4=$(t)

# A failed count must not swallow the whole summary. The 1.7M-account run finished, reconciled and
# printed its timings — then died on one of these queries under `set -e`, losing the row counts and
# recon's own lines. Report the failure in place of the number and carry on.
q() { podman exec "$NAME" psql -U mig -d mig -tAc "$1" 2>&1 | head -1 || echo "query-failed"; }
{
  echo "=== scale run: N=${N} accounts (${DATA_RECORDS} data records, $(du -h "$SRC" | cut -f1)) ==="
  echo "intake   $(elapsed "$t0" "$t1")s"
  echo "curate   $(elapsed "$t1" "$t2")s"
  echo "produce  $(elapsed "$t2" "$t3")s"
  echo "recon    $(elapsed "$t3" "$t4")s   (exit ${recon_exit})"
  echo "TOTAL    $(elapsed "$t0" "$t4")s   => $(python3 -c "print(f'{$N / ($t4 - $t0):.0f}')") accounts/s end to end"
  grep -E "^recon:" "$WORK/recon.log" | sed 's/^/  /'
  echo "rows: account_src=$(q "SELECT count(*) FROM etlr_extraction.account_src") curated=$(q "SELECT count(*) FROM etlr_transformation.account_curated") enriched=$(q "SELECT count(*) FROM etlr_transformation.account_enriched") target=$(q "SELECT count(*) FROM etlr_transformation.account_target") reject_log=$(q "SELECT count(*) FROM etlr_recon.reject_log")"
  echo "expected: written=${N} rejected=5 src_read=${DATA_RECORDS} batches=$(( (N + 199) / 200 ))"
} | tee -a "$RESULTS"
exit "$recon_exit"
