#!/bin/bash
# Orchestrator — replaces the Composer DAG's task sequence, run as
# one Cloud Run Job execution, triggered every minute by Cloud Scheduler.
#
#   discover runs -> [per run] done/failed? -> lock -> attempt cap -> wait_for_extract_flg
#     -> gate -> flink_intake -> flink_curate_enrich -> flink_produce -> recon -> done marker
#
# Two modes:
#   RUN_ID set    process exactly that run (the original behaviour; still what a subprocess does)
#   RUN_ID unset  DISCOVER: every extraction/<run_id>/incoming/ACCOUNT.FLG that has neither a
#                 done/ nor a failed/ marker is processed, each in its own subprocess so that a
#                 failure in one run neither aborts the others nor leaks its lock or its `set -e`
#                 state into the next.
#
# Idempotent by construction: every stage cleans its own run_id's
# prior rows before writing, so re-running this whole sequence on the same RUN_ID (e.g.
# because a previous execution died mid-sequence) is safe, not just tolerated.
#
# Everything a test needs to redirect is an environment variable with the production default
# (MOUNT, APP_DIR, MANIFEST_DIR, MIG_ROOT, WORK_TMP), so OrchestratorBehaviourTest can run this
# very script against stub gsutil/kubectl/java rather than a copy of its logic.
set -euo pipefail

: "${GCS_LANDING_BUCKET:?}"
: "${CLOUDSQL_JDBC_URL:?}"
: "${CLOUDSQL_APP_USER:?}"
: "${FLINK_GKE_CLUSTER:?}"
: "${GCP_REGION:?}"
: "${GCP_PROJECT:?}"

MOUNT="${MOUNT:-/mnt/gcs}"
APP_DIR="${APP_DIR:-/app}"
MANIFEST_DIR="${MANIFEST_DIR:-/manifests}"
MIG_ROOT="${MIG_ROOT:-/opt/mig}"
WORK_TMP="${WORK_TMP:-/tmp}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"
LOCK_TTL_SECONDS="${LOCK_TTL_SECONDS:-2700}"
FLINK_WAIT_POLLS="${FLINK_WAIT_POLLS:-60}"
FLINK_POLL_SECONDS="${FLINK_POLL_SECONDS:-10}"
BUCKET="gs://${GCS_LANDING_BUCKET}"

# ── DISCOVER mode ────────────────────────────────────────────────────────────
if [ -z "${RUN_ID:-}" ]; then
  echo "orchestrator: no RUN_ID given — discovering runs under ${BUCKET}/extraction/"
  # `|| true`: an empty bucket makes `gsutil ls` fail, and "nothing to do" is not an error.
  flags="$(gsutil ls "${BUCKET}/extraction/*/incoming/ACCOUNT.FLG" 2>/dev/null || true)"
  discovered=0
  failed_any=0
  while IFS= read -r flg; do
    [ -z "$flg" ] && continue
    id="$(echo "$flg" | sed -n 's|.*/extraction/\([^/]*\)/incoming/ACCOUNT\.FLG$|\1|p')"
    [ -z "$id" ] && continue
    # Check the markers HERE as well as inside the subprocess: a finished run is the common
    # case (every firing, forever), and spawning a process to learn that would cost a JVM-free
    # but still wasteful `bash` + env setup per finished run per minute.
    if gsutil -q stat "${BUCKET}/done/${id}.done" 2>/dev/null; then continue; fi
    if gsutil -q stat "${BUCKET}/failed/${id}.failed" 2>/dev/null; then continue; fi
    if ! [[ "$id" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || [ "${#id}" -gt 49 ]; then
      echo "orchestrator: WARNING skipping '${id}' — not a valid run id (see the check below); rename its extraction/ directory" >&2
      failed_any=1
      continue
    fi
    discovered=$((discovered + 1))
    echo "orchestrator: discovered pending run ${id}"
    if ! RUN_ID="$id" bash "$0"; then
      echo "orchestrator: run ${id} did not complete this firing" >&2
      failed_any=1
    fi
  done <<< "$flags"
  echo "orchestrator: discovery finished — ${discovered} pending run(s)"
  exit "$failed_any"
fi

echo "orchestrator: run_id=${RUN_ID}"

# The run id becomes a Kubernetes resource name (flink-produce-<id>), so it must be a DNS-1123 label:
# lowercase alphanumerics and '-', starting and ending alphanumeric, and short enough that the longest
# prefix ("flink-produce-", 14 chars) still fits in 63. Discovery takes ids straight from bucket paths,
# so "Run_1" or "2026/09" is reachable input — and without this check it travels the whole way to
# `kubectl apply` and fails there with an opaque API error, once per attempt.
if ! [[ "${RUN_ID}" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || [ "${#RUN_ID}" -gt 49 ]; then
  echo "orchestrator: run id '${RUN_ID}' is not usable as a Kubernetes name (lowercase letters, digits and '-', at most 49 characters, starting and ending alphanumeric) — refusing to run it" >&2
  exit 1
fi

# ── credentials ──────────────────────────────────────────────────────────────
# The Cloud SQL password comes from Secret Manager when CLOUDSQL_APP_PASSWORD_SECRET is set,
# and only from a plain environment variable as the fallback. The
# environment-variable path is what the earlier deploys used and stays for local runs and tests.
if [ -n "${CLOUDSQL_APP_PASSWORD_SECRET:-}" ]; then
  CLOUDSQL_APP_PASSWORD="$(gcloud secrets versions access latest --secret="${CLOUDSQL_APP_PASSWORD_SECRET}" --project="$GCP_PROJECT")"
fi
: "${CLOUDSQL_APP_PASSWORD:?set CLOUDSQL_APP_PASSWORD or CLOUDSQL_APP_PASSWORD_SECRET}"

# ── already-finished / already-failed markers ────────────────────────────────
# Cloud Scheduler fires every minute and the trigger is a FILE (the .FLG), which is still
# sitting in the bucket after the lane succeeds. Without a "this run is done" marker every
# later firing would see the .FLG, take the lock, and run the whole sequence again: three
# Flink submissions and 50 re-published documents per minute, indefinitely. Idempotent,
# so it would never corrupt anything — which is exactly why nothing would ever
# flag it, only the bill. The marker is written last, after recon passes, so a FAILED run
# leaves no marker and is retried on the next firing, as intended. To deliberately re-run a
# run_id, delete the marker.
#
# The failed/ marker is the same idea for the opposite outcome: a run that keeps failing must
# STOP, not retry once a minute for ever. See the attempt cap below.
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

# ── overlapping-execution lock ───────────────────────────────────────────────
#  flagged this and left it unwritten: Cloud Scheduler fires every minute
# regardless of whether the previous execution has finished, and google_cloud_run_v2_job
# has no equivalent of a Kubernetes CronJob's concurrencyPolicy. A full lane run takes
# minutes, so without a lock the second, third and fourth firings all start the same
# run_id concurrently — three Flink jobs writing the same rows, and a balancing equation
# that fails for a reason that looks like data loss.
#
# The lock is a GCS object created with `x-goog-if-generation-match:0`, which GCS honours
# ATOMICALLY: exactly one writer wins when several race, and the losers get a 412. That is
# the whole mechanism — no lease service, no extra dependency.
#
# Staleness: the lock carries the acquiring execution's id and a timestamp, and is
# force-broken after LOCK_TTL_SECONDS (default 45 minutes, comfortably longer than a full
# run) so a killed execution cannot wedge the lane forever. That is a deliberate
# trade — a lock that can be broken beats a lane that stops — and it is safe here only
# because every stage is idempotent : the worst case is duplicated work, not
# duplicated rows.
LOCK="${BUCKET}/locks/${RUN_ID}.lock"
EXECUTION_ID="${CLOUD_RUN_EXECUTION:-$(hostname)-$$}"

release_lock() {
  gsutil -q rm "$LOCK" 2>/dev/null || true
}

if ! printf '%s\n%s\n' "$EXECUTION_ID" "$(date +%s)" \
     | gsutil -q -h "x-goog-if-generation-match:0" cp - "$LOCK" 2>/dev/null; then
  held_at="$(gsutil cat "$LOCK" 2>/dev/null | sed -n 2p || true)"
  # An unreadable lock is NOT a stale one. It is unreadable when its holder is deleting it at this
  # very moment (found on GCP at 200k: "age 1790457671s" — the epoch — so a lock in the middle of
  # being released was "broken" and a second full attempt started), or on a transient read error.
  # Treating "no timestamp" as "held since 1970" turns both into a false break.
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

# ── wait_for_extract_flg ────────────────────────────────────────────────────
# harness/bundle.py writes the bundle to <run_id>/incoming/ (ACCOUNT.tar.gz + ACCOUNT.FLG
# beside it, the .FLG last and outside the archive so "the extract is complete" can be
# detected without decrypting anything). gate reads that same incoming/ directory,
# so the sensor watches the .FLG THERE — an earlier draft watched one level up, where
# nothing ever writes it, which would have left the lane waiting forever with no error.
FLG="${BUCKET}/extraction/${RUN_ID}/incoming/ACCOUNT.FLG"
if ! FLG_STAT="$(gsutil stat "$FLG" 2>/dev/null)"; then
  echo "orchestrator: ${FLG} not found yet — nothing to do this minute"
  exit 0
fi
echo "orchestrator: found ${FLG}"

# ── attempt cap ──────────────────────────────────────────────────────────────
# `max_retries = 0` on the Cloud Run Job only stops CLOUD RUN retrying; the scheduler fires every
# minute, and a persistent failure re-ran intake -> curate -> produce -> recon each time, each with
# GKE pods and 50 re-published messages (observed live: three failures, three full re-runs).
# Idempotent, so nothing corrupted — which is why nothing would ever flag it. Count real attempts
# (this point is only reached once the .FLG exists and the lock is ours) and stop at MAX_ATTEMPTS
# with a marker and a loud line for a log-based alert to match on.
attempt="$(gsutil cat "$ATTEMPTS" 2>/dev/null | head -1 || true)"
attempt=$(( ${attempt:-0} + 1 ))
if [ "$attempt" -gt "$MAX_ATTEMPTS" ]; then
  printf 'failed %s after %s attempts by %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$MAX_ATTEMPTS" "$EXECUTION_ID" \
    | gsutil -q cp - "$FAILED"
  echo "orchestrator: ALERT run ${RUN_ID} FAILED PERMANENTLY after ${MAX_ATTEMPTS} attempts — marker ${FAILED}; delete it and ${ATTEMPTS} to retry" >&2
  exit 0
fi
printf '%s\n' "$attempt" | gsutil -q cp - "$ATTEMPTS"
echo "orchestrator: attempt ${attempt} of ${MAX_ATTEMPTS}"

# The as-of date of the curated data is part of its content (account_age_years), so it must be a
# property of the RUN, not of the day a retry happens to fire: two attempts of one run_id on
# either side of midnight — or New Year — would otherwise produce different curated tables
#. The .FLG is written last by the producer, so its creation time is the
# moment the extract was declared complete: stable across every retry. AS_OF_DATE overrides it.
if [ -z "${AS_OF_DATE:-}" ]; then
  flg_time="$(echo "${FLG_STAT}" | sed -n 's/^ *Creation time: *//p' | head -1)"
  # An EMPTY string must be rejected before it reaches `date`: `date -d ""` is not an error, it is
  # midnight today — which would turn "unreadable" into "today" with no message, the exact
  # silent drift this block exists to prevent (found by OrchestratorBehaviourTest).
  AS_OF_DATE=""
  if [ -n "${flg_time}" ]; then
    AS_OF_DATE="$(date -u -d "${flg_time}" +%Y-%m-%d 2>/dev/null || true)"
  fi
  if [ -z "${AS_OF_DATE}" ]; then
    AS_OF_DATE="$(date -u +%Y-%m-%d)"
    echo "orchestrator: could not read the .FLG creation time — falling back to today (${AS_OF_DATE})" >&2
  fi
fi
echo "orchestrator: as_of_date=${AS_OF_DATE}"

# ── PGP: keys for an encrypted delivery ───────────────
# The delivery format is .tar.gz.pgp. If that is what arrived, fetch the
# private key and passphrase from Secret Manager into files only this process can read, and let
# gate find them through the environment. A plain .tar.gz needs none of this.
if gsutil -q stat "${BUCKET}/extraction/${RUN_ID}/incoming/ACCOUNT.tar.gz.pgp" 2>/dev/null; then
  echo "orchestrator: encrypted bundle (.pgp) — fetching key material from Secret Manager"
  umask 077
  export PGP_PRIVATE_KEY_FILE="${WORK_TMP}/pgp-private.asc"
  # Terraform creates these two secrets but deliberately does NOT write a value into them: the key
  # comes from whoever operates the extract, out of band, and must never pass through Terraform state.
  # So "the secret exists and is empty" is the EXPECTED state of a fresh environment, and the failure
  # mode is an operator forgetting the step — say exactly what to do instead of dying on gcloud's error.
  pgp_missing() {
    echo "orchestrator: an encrypted bundle arrived but the secret '$1' has no readable version. An operator must add the key material (Terraform intentionally does not):  gcloud secrets versions add $1 --data-file=<file> --project=${GCP_PROJECT}" >&2
    exit 1
  }
  PGP_KEY_SECRET="${PGP_PRIVATE_KEY_SECRET:-pgp-private-key}"
  PGP_PASS_SECRET="${PGP_PASSPHRASE_SECRET:-pgp-passphrase}"
  gcloud secrets versions access latest --secret="$PGP_KEY_SECRET" --project="$GCP_PROJECT" > "$PGP_PRIVATE_KEY_FILE" || pgp_missing "$PGP_KEY_SECRET"
  PGP_PASSPHRASE="$(gcloud secrets versions access latest --secret="$PGP_PASS_SECRET" --project="$GCP_PROJECT")" || pgp_missing "$PGP_PASS_SECRET"
  export PGP_PASSPHRASE
fi

# ── gate ─────────────────────────────────────────────────────────────
# GCS mounted at /mnt/gcs via Cloud Run's native GCS volume mount (module.scheduler's
# `volumes { gcs { ... } }` —  chosen workaround: Gate's
# existing filesystem code runs unchanged against the mounted path, no new GCS Java
# client, no manual gcsfuse binary. Cloud Run Jobs run inside a gVisor sandbox that
# blocks raw /dev/fuse, so the native mount — not a shelled-out `gcsfuse` process — is
# what actually works here.
GATE_OUT="$(java -jar "${APP_DIR}/gate.jar" \
  "${MOUNT}/extraction/${RUN_ID}/incoming" ACCOUNT "${RUN_ID}" "${MOUNT}/work" "${MIG_ROOT}")"
echo "${GATE_OUT}"
echo "orchestrator: gate OK"

# ── flink_intake / flink_curate_enrich / flink_produce ─────────────────────────────
gcloud container clusters get-credentials "$FLINK_GKE_CLUSTER" --region "$GCP_REGION" --project "$GCP_PROJECT"

# How the Flink jobs get the database password.
#   secret (default)  a Kubernetes Secret `mig-db` holds it; the manifests pass the literal argument
#                     `env:JDBC_PASSWORD` and mount the secret as that environment variable, so the
#                     password is in neither the FlinkDeployment spec, the program arguments, nor the
#                     JobManager's "Loading configuration property" log lines (all three showed it).
#   arg               the old behaviour: the password is substituted into the manifest as an argument.
DB_PASSWORD_MODE="${DB_PASSWORD_MODE:-secret}"
if [ "$DB_PASSWORD_MODE" = "secret" ]; then
  kubectl -n flink-operator create secret generic mig-db --from-literal=password="${CLOUDSQL_APP_PASSWORD}" \
    --dry-run=client -o yaml | kubectl apply -f -
  JDBC_PASSWORD_VALUE="env:JDBC_PASSWORD"
else
  JDBC_PASSWORD_VALUE="${CLOUDSQL_APP_PASSWORD}"
fi

for job in flink_intake flink_curate_enrich flink_produce; do
  manifest="${WORK_TMP}/${job}.yaml"
  sed -e "s|\${RUN_ID}|${RUN_ID}|g" \
      -e "s|\${LANDING_BUCKET}|${GCS_LANDING_BUCKET}|g" \
      -e "s|\${JDBC_URL}|${CLOUDSQL_JDBC_URL}|g" \
      -e "s|\${JDBC_USER}|${CLOUDSQL_APP_USER}|g" \
      -e "s|\${JDBC_PASSWORD}|${JDBC_PASSWORD_VALUE}|g" \
      -e "s|\${IMAGE_TAG}|${FLINK_IMAGE_TAG:-latest}|g" \
      -e "s|REGION-docker.pkg.dev/PROJECT_ID|${GCP_REGION}-docker.pkg.dev/${GCP_PROJECT}|g" \
      -e "s|\${AS_OF_DATE}|${AS_OF_DATE}|g" \
      -e "s|\${KAFKA_BOOTSTRAP}|${KAFKA_BOOTSTRAP:-}|g" \
      -e "s|\${KAFKA_TARGET_TOPIC}|${TARGET_SYSTEM_TARGET_TOPIC:-target-system-target}|g" \
      -e "s|\${KAFKA_SECURITY_PROTOCOL}|${KAFKA_SECURITY_PROTOCOL:-PLAINTEXT}|g" \
      "${MANIFEST_DIR}/${job}.yaml.template" > "$manifest"

  # Clear any previous FlinkDeployment of this name before submitting. A FAILED one is left in
  # place on purpose (it is the only place its logs live, and the loop below exits without
  # deleting it), but the operator will NOT re-run a resource whose spec has not changed — so
  # `kubectl apply` of an identical manifest onto a failed one is a silent no-op that reads
  # FAILED again immediately. Without this, one transient failure (or one bug, once fixed)
  # would fail every later retry instantly, and the lane would need manual cleanup to move.
  kubectl delete -f "$manifest" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kubectl apply -f "$manifest"
  name="$(grep -oP '(?<=name: ).*' "$manifest" | head -1)"
  echo "orchestrator: submitted ${name}, waiting for FINISHED..."
  state=""
  for i in $(seq 1 "$FLINK_WAIT_POLLS"); do
    state="$(kubectl get flinkdeployment "$name" -n flink-operator -o jsonpath='{.status.jobStatus.state}' 2>/dev/null || echo '')"
    if [ "$state" = "FINISHED" ]; then
      echo "orchestrator: ${name} FINISHED"
      break
    fi
    if [ "$state" = "FAILED" ]; then
      echo "orchestrator: ${name} FAILED" >&2
      exit 1
    fi
    sleep "$FLINK_POLL_SECONDS"
  done
  if [ "$state" != "FINISHED" ]; then
    echo "orchestrator: ${name} did not finish within timeout (state=${state})" >&2
    exit 1
  fi
  kubectl delete -f "$manifest" --ignore-not-found
done

# ── recon ────────────────────────────────────────────────────────────────────
# recon's src_read is the number of DATA records the producer sent — and that is NOT the .CHS
# "records" column. The .CHS counts physical lines of the delivered file (header included; the
# fixture is 56 lines and 55 records), because it is an integrity check on the file as shipped.
# Using it directly gave `56 != 50 + 5` on the first real run, with the settle loop already
# passing. gate knows the contract and reports the data-record count itself, so it is
# read from there rather than re-derived in shell (where "is there a header?" would be a second
# opinion about a question the TDS already answers). The .CHS is still verified — by the gate,
# before anything else runs.
SRC_READ="$(echo "${GATE_OUT}" | sed -n 's/.*data_records=\([0-9][0-9]*\).*/\1/p' | tail -1)"
if [ -z "${SRC_READ}" ] || [ "${SRC_READ}" -le 0 ]; then
  echo "orchestrator: gate did not report a data-record count — refusing to reconcile" >&2
  exit 1
fi
echo "orchestrator: src_read=${SRC_READ} (data records, from gate)"

# recon's JSON + HTML report defaults to a relative path, which on Cloud Run
# is the container's throwaway filesystem — gone when the execution exits. Writing it through
# the mounted landing bucket makes it a retrievable artefact of the run instead.
export RECON_REPORT_DIR="${MOUNT}/recon-reports"

if [ -n "${KAFKA_BOOTSTRAP:-}" ]; then
  java -jar "${APP_DIR}/recon-service.jar" \
    "${RUN_ID}" "${SRC_READ}" "${CLOUDSQL_JDBC_URL}" "${CLOUDSQL_APP_USER}" "${CLOUDSQL_APP_PASSWORD}" \
    "${KAFKA_BOOTSTRAP}" "${TARGET_SYSTEM_CONFIRMATION_TOPIC:-target-system-confirmations}" "120"
else
  java -jar "${APP_DIR}/recon-service.jar" \
    "${RUN_ID}" "${SRC_READ}" "${CLOUDSQL_JDBC_URL}" "${CLOUDSQL_APP_USER}" "${CLOUDSQL_APP_PASSWORD}"
fi

# Only reached if recon exited 0 (`set -e`): the marker means "this run reconciled", never
# just "this run finished". Clearing the attempt counter on success keeps a later deliberate
# re-run (marker deleted) from starting with a stale count.
printf 'completed %s by %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$EXECUTION_ID" | gsutil -q cp - "$DONE"
gsutil -q rm "$ATTEMPTS" 2>/dev/null || true
echo "orchestrator: run ${RUN_ID} complete — marker written to ${DONE}"
