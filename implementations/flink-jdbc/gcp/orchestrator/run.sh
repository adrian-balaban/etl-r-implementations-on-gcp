#!/bin/bash
# flink-jdbc orchestrator — adapted from ../../../flink/gcp/orchestrator/run.sh. One Cloud Run Job execution per
# Cloud Scheduler firing (every minute).
#
#   discover (gate-jdbc --discover) -> [per run] done/failed? -> lock -> gate-jdbc -> attempt cap
#     -> flink_intake_jdbc -> flink_curate_enrich -> flink_produce -> recon-jdbc -> recon -> done marker
#
# Kept from the Flink lane: the GCS lock, the done/ and failed/ markers, MAX_ATTEMPTS, one subprocess per run.
# Changed: discovery asks the source (control rows) instead of scanning for .FLG files; the as-of date is the UTC
# date of the control row's ready_at; gate-jdbc's and recon-jdbc's exit codes decide what a failure means:
#   gate 3 (refused)  not ready: exit 0, no attempt counted, the next tick asks again
#   gate 4 (failed)   terminal: failed/ marker at once, ALERT, no retry loop
#   recon-jdbc 4      terminal, the same way (the source changed after the gate)
#   anything else     an ordinary attempt, retried until MAX_ATTEMPTS
# Every stage is idempotent per run_id (intake deletes the run's rows first), so a retry is safe.
#
# Every path a test redirects is an environment variable with the production default, so
# JdbcOrchestratorBehaviourTest runs this very script against stubs.
set -euo pipefail

: "${GCS_LANDING_BUCKET:?}"
: "${CLOUDSQL_JDBC_URL:?}"
: "${CLOUDSQL_APP_USER:?}"
: "${SOURCE_JDBC_URL:?}"
: "${FLINK_GKE_CLUSTER:?}"
: "${GCP_REGION:?}"
: "${GCP_PROJECT:?}"

SOURCE_READER_USER="${SOURCE_READER_USER:-etlr_reader}"
MOUNT="${MOUNT:-/mnt/gcs}"
APP_DIR="${APP_DIR:-/app}"
MANIFEST_DIR="${MANIFEST_DIR:-/manifests}"
WORK_TMP="${WORK_TMP:-/tmp}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"
LOCK_TTL_SECONDS="${LOCK_TTL_SECONDS:-2700}"
FLINK_WAIT_POLLS="${FLINK_WAIT_POLLS:-60}"
FLINK_POLL_SECONDS="${FLINK_POLL_SECONDS:-10}"
BUCKET="gs://${GCS_LANDING_BUCKET}"
# The longest FlinkDeployment name is flink-curate-enrich-<id> (20 + id); the operator adds a "<name>-rest"
# Service, and a Service name is a 63-character DNS-1035 label: 63 - 20 - 5 = 38.
RUN_ID_MAX=38

valid_run_id() { [[ "$1" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] && [ "${#1}" -le "$RUN_ID_MAX" ]; }

# ── credentials (needed by discovery too) ────────────────────────────────────
# From Secret Manager when *_SECRET is set (../../../flink/); fetched once per firing and
# exported, so subprocesses inherit them. Java receives only "env:NAME" references (mig.common.ConnectionArgs).
fetch_secret() { gcloud secrets versions access latest --secret="$1" --project="$GCP_PROJECT"; }
if [ -z "${CLOUDSQL_APP_PASSWORD:-}" ] && [ -n "${CLOUDSQL_APP_PASSWORD_SECRET:-}" ]; then
  CLOUDSQL_APP_PASSWORD="$(fetch_secret "$CLOUDSQL_APP_PASSWORD_SECRET")"
fi
if [ -z "${SOURCE_READER_PASSWORD:-}" ] && [ -n "${SOURCE_READER_PASSWORD_SECRET:-}" ]; then
  SOURCE_READER_PASSWORD="$(fetch_secret "$SOURCE_READER_PASSWORD_SECRET")"
fi
: "${CLOUDSQL_APP_PASSWORD:?set CLOUDSQL_APP_PASSWORD or CLOUDSQL_APP_PASSWORD_SECRET}"
: "${SOURCE_READER_PASSWORD:?set SOURCE_READER_PASSWORD or SOURCE_READER_PASSWORD_SECRET}"
export CLOUDSQL_APP_PASSWORD SOURCE_READER_PASSWORD

discover() {
  java -jar "${APP_DIR}/gate-jdbc.jar" --discover "$SOURCE_JDBC_URL" "$SOURCE_READER_USER" env:SOURCE_READER_PASSWORD
}

# ── DISCOVER mode ────────────────────────────────────────────────────────────
if [ -z "${RUN_ID:-}" ]; then
  echo "orchestrator: no RUN_ID given — discovering runs the source has vouched for"
  if ! listing="$(discover)"; then
    echo "orchestrator: gate-jdbc --discover failed — the source is unreachable or refused ${SOURCE_READER_USER}; nothing processed this firing" >&2
    exit 1
  fi
  discovered=0
  failed_any=0
  while IFS=$'\t' read -r id ready_at; do
    [ -z "$id" ] && continue
    if gsutil -q stat "${BUCKET}/done/${id}.done" 2>/dev/null; then continue; fi
    if gsutil -q stat "${BUCKET}/failed/${id}.failed" 2>/dev/null; then continue; fi
    if ! valid_run_id "$id"; then
      echo "orchestrator: WARNING skipping '${id}' — not a valid run id (lowercase, digits, '-', at most ${RUN_ID_MAX} characters)" >&2
      failed_any=1
      continue
    fi
    discovered=$((discovered + 1))
    echo "orchestrator: discovered pending run ${id} (ready_at ${ready_at})"
    if ! RUN_ID="$id" READY_AT="$ready_at" bash "$0"; then
      echo "orchestrator: run ${id} did not complete this firing" >&2
      failed_any=1
    fi
  done <<< "$listing"
  echo "orchestrator: discovery finished — ${discovered} pending run(s)"
  exit "$failed_any"
fi

echo "orchestrator: run_id=${RUN_ID}"
if ! valid_run_id "$RUN_ID"; then
  echo "orchestrator: run id '${RUN_ID}' is not usable as a Kubernetes name (lowercase letters, digits and '-', at most ${RUN_ID_MAX} characters, starting and ending alphanumeric) — refusing to run it" >&2
  exit 1
fi

# ── markers ──────────────────────────────────────────────────────────────────
DONE="${BUCKET}/done/${RUN_ID}.done"
FAILED="${BUCKET}/failed/${RUN_ID}.failed"
ATTEMPTS="${BUCKET}/attempts/${RUN_ID}.count"
if gsutil -q stat "$DONE" 2>/dev/null; then
  echo "orchestrator: ${RUN_ID} already completed (${DONE}) — nothing to do"
  exit 0
fi
if gsutil -q stat "$FAILED" 2>/dev/null; then
  echo "orchestrator: ${RUN_ID} is marked failed (${FAILED}) — delete that object (and ${ATTEMPTS}) to retry"
  exit 0
fi

# ── lock (the Flink lane's, unchanged: atomic create-if-absent, unreadable != stale) ─────
LOCK="${BUCKET}/locks/${RUN_ID}.lock"
EXECUTION_ID="${CLOUD_RUN_EXECUTION:-$(hostname)-$$}"
release_lock() { gsutil -q rm "$LOCK" 2>/dev/null || true; }
if ! printf '%s\n%s\n' "$EXECUTION_ID" "$(date +%s)" \
     | gsutil -q -h "x-goog-if-generation-match:0" cp - "$LOCK" 2>/dev/null; then
  held_at="$(gsutil cat "$LOCK" 2>/dev/null | sed -n 2p || true)"
  case "$held_at" in
    ''|*[!0-9]*)
      echo "orchestrator: lock ${LOCK} exists but its age is unreadable — exiting cleanly, the next firing retries"
      exit 0 ;;
  esac
  age=$(( $(date +%s) - held_at ))
  if [ "$age" -lt "$LOCK_TTL_SECONDS" ]; then
    echo "orchestrator: another execution holds ${LOCK} (age ${age}s) — exiting cleanly"
    exit 0
  fi
  echo "orchestrator: breaking stale lock ${LOCK} (age ${age}s > ${LOCK_TTL_SECONDS}s)" >&2
  release_lock
  printf '%s\n%s\n' "$EXECUTION_ID" "$(date +%s)" | gsutil -q cp - "$LOCK"
fi
trap release_lock EXIT
echo "orchestrator: lock acquired (${EXECUTION_ID})"

mark_failed() {
  printf 'failed %s by %s: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$EXECUTION_ID" "$1" | gsutil -q cp - "$FAILED"
  echo "orchestrator: ALERT run ${RUN_ID} FAILED PERMANENTLY: $1 — marker ${FAILED}; delete it and ${ATTEMPTS} to retry" >&2
}

take_attempt() {
  local attempt
  attempt="$(gsutil cat "$ATTEMPTS" 2>/dev/null | head -1 || true)"
  attempt=$(( ${attempt:-0} + 1 ))
  if [ "$attempt" -gt "$MAX_ATTEMPTS" ]; then
    mark_failed "after ${MAX_ATTEMPTS} attempts"
    exit 0
  fi
  printf '%s\n' "$attempt" | gsutil -q cp - "$ATTEMPTS"
  echo "orchestrator: attempt ${attempt} of ${MAX_ATTEMPTS}"
}

# ── gate-jdbc ────────────────────────────────────────────────────────────────
gate_rc=0
GATE_OUT="$(java -jar "${APP_DIR}/gate-jdbc.jar" "$RUN_ID" "$SOURCE_JDBC_URL" "$SOURCE_READER_USER" env:SOURCE_READER_PASSWORD)" || gate_rc=$?
case "$gate_rc" in
  0) ;;
  3)
    echo "orchestrator: ${RUN_ID} not ready — the source has no control row for it yet (gate-jdbc refused); nothing to do this minute"
    exit 0 ;;
  4)
    mark_failed "gate-jdbc: the source table disagrees with its own control row (count, content digest or a duplicate key); zero rows written"
    exit 1 ;;
  *)
    echo "orchestrator: gate-jdbc exited ${gate_rc} — not a verdict (connection, credentials or usage); counted as an attempt" >&2
    take_attempt
    exit 1 ;;
esac
take_attempt
SRC_READ="$(echo "${GATE_OUT}" | sed -n 's/^src_read=\([0-9][0-9]*\)$/\1/p' | tail -1)"
if [ -z "${SRC_READ}" ]; then
  echo "orchestrator: gate-jdbc passed but printed no src_read — refusing to continue" >&2
  exit 1
fi
echo "orchestrator: gate OK, src_read=${SRC_READ}"

# ── as-of date: the UTC date of the control row's ready_at ───────────────────
if [ -z "${AS_OF_DATE:-}" ]; then
  if [ -z "${READY_AT:-}" ]; then
    READY_AT="$(discover 2>/dev/null | awk -F'\t' -v id="$RUN_ID" '$1 == id { print $2 }' | tail -1 || true)"
  fi
  AS_OF_DATE=""
  if [ -n "${READY_AT:-}" ]; then
    AS_OF_DATE="$(date -u -d "${READY_AT}" +%Y-%m-%d 2>/dev/null || true)"
  fi
  if [ -z "${AS_OF_DATE}" ]; then
    AS_OF_DATE="$(date -u +%Y-%m-%d)"
    echo "orchestrator: could not read the control row's ready_at — falling back to today (${AS_OF_DATE})" >&2
  fi
fi
echo "orchestrator: as_of_date=${AS_OF_DATE}"

# ── the three Flink jobs ─────────────────────────────────────────────────────
gcloud container clusters get-credentials "$FLINK_GKE_CLUSTER" --region "$GCP_REGION" --project "$GCP_PROJECT"
kubectl -n flink-operator create secret generic mig-db --from-literal=password="${CLOUDSQL_APP_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl -n flink-operator create secret generic etlr-source-db --from-literal=password="${SOURCE_READER_PASSWORD}" \
  --dry-run=client -o yaml | kubectl apply -f -

for job in flink_intake_jdbc flink_curate_enrich flink_produce; do
  manifest="${WORK_TMP}/${job}.yaml"
  # curate and produce are the Flink lane's templates: point them at this lane's image and jar.
  sed -e "s|\${RUN_ID}|${RUN_ID}|g" \
      -e "s|\${JDBC_URL}|${CLOUDSQL_JDBC_URL}|g" \
      -e "s|\${JDBC_USER}|${CLOUDSQL_APP_USER}|g" \
      -e "s|\${JDBC_PASSWORD}|env:JDBC_PASSWORD|g" \
      -e "s|\${SOURCE_JDBC_URL}|${SOURCE_JDBC_URL}|g" \
      -e "s|\${SOURCE_USER}|${SOURCE_READER_USER}|g" \
      -e "s|\${IMAGE_TAG}|${FLINK_IMAGE_TAG:-latest}|g" \
      -e "s|REGION-docker.pkg.dev/PROJECT_ID|${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT}|g" \
      -e "s|/mig-flink/flink-job:|/mig-flink/flink-jdbc-job:|g" \
      -e "s|local:///opt/flink/usrlib/job.jar|local:///opt/flink/usrlib/flink-jdbc-job.jar|g" \
      -e "s|\${AS_OF_DATE}|${AS_OF_DATE}|g" \
      -e "s|\${KAFKA_BOOTSTRAP}|${KAFKA_BOOTSTRAP:-}|g" \
      -e "s|\${KAFKA_TARGET_TOPIC}|${TARGET_SYSTEM_TARGET_TOPIC:-target-system-target}|g" \
      -e "s|\${KAFKA_SECURITY_PROTOCOL}|${KAFKA_SECURITY_PROTOCOL:-PLAINTEXT}|g" \
      "${MANIFEST_DIR}/${job}.yaml.template" > "$manifest"
  if grep -v '^[[:space:]]*#' "$manifest" | grep -q '\${'; then
    echo "orchestrator: ${manifest} still has an unrendered placeholder — refusing to apply it" >&2
    exit 1
  fi
  # A FAILED FlinkDeployment of the same name makes `kubectl apply` a silent no-op; delete it first.
  kubectl delete -f "$manifest" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kubectl apply -f "$manifest"
  name="$(sed -n 's/^  name: //p' "$manifest" | head -1)"
  echo "orchestrator: submitted ${name}, waiting for FINISHED..."
  state=""
  for _ in $(seq 1 "$FLINK_WAIT_POLLS"); do
    state="$(kubectl get flinkdeployment "$name" -n flink-operator -o jsonpath='{.status.jobStatus.state}' 2>/dev/null || echo '')"
    if [ "$state" = "FINISHED" ]; then echo "orchestrator: ${name} FINISHED"; break; fi
    if [ "$state" = "FAILED" ]; then echo "orchestrator: ${name} FAILED" >&2; exit 1; fi
    sleep "$FLINK_POLL_SECONDS"
  done
  if [ "$state" != "FINISHED" ]; then
    echo "orchestrator: ${name} did not finish within timeout (state=${state})" >&2
    exit 1
  fi
  kubectl delete -f "$manifest" --ignore-not-found
done

# ── recon-jdbc: did intake read what the source vouched for? ─────────────────
rj_rc=0
java -cp "${APP_DIR}/gate-jdbc.jar" mig.gatejdbc.ReconJdbc "$RUN_ID" \
  "$SOURCE_JDBC_URL" "$SOURCE_READER_USER" env:SOURCE_READER_PASSWORD \
  "$CLOUDSQL_JDBC_URL" "$CLOUDSQL_APP_USER" env:CLOUDSQL_APP_PASSWORD || rj_rc=$?
case "$rj_rc" in
  0) ;;
  4) mark_failed "recon-jdbc: intake did not read what the source vouched for (the source changed after the gate)"; exit 1 ;;
  *) echo "orchestrator: recon-jdbc exited ${rj_rc}" >&2; exit 1 ;;
esac

# ── recon (balance + settle) ─────────────────────────────────────────────────
export RECON_REPORT_DIR="${MOUNT}/recon-reports"
recon_args=("${RUN_ID}" "${SRC_READ}" "${CLOUDSQL_JDBC_URL}" "${CLOUDSQL_APP_USER}" "env:CLOUDSQL_APP_PASSWORD")
if [ -n "${KAFKA_BOOTSTRAP:-}" ]; then
  recon_args+=("${KAFKA_BOOTSTRAP}" "${TARGET_SYSTEM_CONFIRMATION_TOPIC:-target-system-confirmations}" "120")
fi
java -jar "${APP_DIR}/recon-service.jar" "${recon_args[@]}"

printf 'completed %s by %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$EXECUTION_ID" | gsutil -q cp - "$DONE"
gsutil -q rm "$ATTEMPTS" 2>/dev/null || true
echo "orchestrator: run ${RUN_ID} complete — marker written to ${DONE}"
