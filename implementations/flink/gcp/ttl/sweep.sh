#!/bin/bash
# TTL sweeper — a kill switch that does not depend on anyone being awake.
#
# The first real GCP attempt overran its own 3-hour stop by ~20 hours because "unconditional
# teardown" needed a person at the right moment. This runs on a schedule inside GCP instead
# (module.ttl_guard: Cloud Scheduler -> Cloud Run Job, hourly) and, once the environment's
# expiry has passed, deletes the things that BILL BY THE HOUR: GKE clusters, Cloud SQL instances,
# Managed Kafka clusters, the VPC connector, the mock's Cloud Run service — and PAUSES the
# orchestrator's scheduler so nothing re-creates work.
#
# What it deliberately is not: a `terraform destroy`. It uses gcloud, so Terraform's state will be
# stale afterwards (a plan will want to re-create what was swept). That is the right trade for a
# kill switch — it has to work with no state, no Terraform and no laptop — and the sweep log says
# so. It leaves alone what costs nothing idle (buckets, secrets, the VPC, Cloud Run *jobs*).
#
# Safe by construction:
#   * DRY_RUN=1 (the default) only reports. The Terraform module sets DRY_RUN=0 explicitly.
#   * It only touches resources whose name starts with NAME_PREFIX (default "mig-flink").
#   * A missing or unreadable expiry means DO NOTHING (fail closed): a bad clock or a deleted
#     object must never be able to trigger a teardown.
#   * Idempotent: sweeping an already-swept project is a no-op.
set -uo pipefail

: "${PROJECT:?}"
: "${REGION:?}"
: "${TTL_BUCKET:?}"
NAME_PREFIX="${NAME_PREFIX:-mig-flink}"
DRY_RUN="${DRY_RUN:-1}"
EXPIRES_OBJECT="gs://${TTL_BUCKET}/ttl/expires-at"

say() { echo "ttl-sweeper: $*"; }

expires="$(gsutil cat "$EXPIRES_OBJECT" 2>/dev/null | head -1 | tr -d '[:space:]')"
if ! [[ "$expires" =~ ^[0-9]+$ ]]; then
  say "no valid expiry at ${EXPIRES_OBJECT} (got '${expires}') — doing nothing (fail closed)"
  exit 0
fi

now="$(date +%s)"
if [ "$now" -lt "$expires" ]; then
  say "environment expires in $(( (expires - now) / 60 )) minutes — nothing to do"
  exit 0
fi

say "EXPIRED $(( (now - expires) / 60 )) minutes ago (expires-at=${expires}, now=${now}); DRY_RUN=${DRY_RUN}; prefix='${NAME_PREFIX}'"

# run "<description>" <command...>: report, and execute only when not a dry run.
failures=0
run() {
  local what="$1"; shift
  if [ "$DRY_RUN" = "1" ]; then
    say "[dry-run] would ${what}"
  else
    say "${what}"
    if ! "$@"; then
      say "FAILED: ${what}" >&2
      failures=$((failures + 1))
    fi
  fi
}

# names_of <gcloud list command...>: only resources under our prefix, one per line.
names_of() {
  "$@" 2>/dev/null | grep "^${NAME_PREFIX}" || true
}

# Stop the trigger first, so nothing starts new work while the expensive parts are going away.
for job in $(names_of gcloud scheduler jobs list --location="$REGION" --project="$PROJECT" --format="value(name.basename())"); do
  run "pause scheduler job ${job}" gcloud scheduler jobs pause "$job" --location="$REGION" --project="$PROJECT" --quiet
done

for c in $(names_of gcloud container clusters list --project="$PROJECT" --format="value(name)"); do
  # --async on every delete: the request is issued and the sweeper moves on. Blocking was the first real
  # run's bug (2026-09-27): GKE (4 min) + Cloud SQL (2 min) + Managed Kafka (~15 min) added up past Cloud
  # Run's 900 s task timeout, so the job was killed while waiting on Kafka and never reached the mock or
  # the VPC connector. The deletes proceed server-side; the next hourly firing re-lists and retries any
  # that failed, and a resource already being deleted is a logged, harmless error.
  run "delete GKE cluster ${c}" gcloud container clusters delete "$c" --region="$REGION" --project="$PROJECT" --quiet --async
done
for i in $(names_of gcloud sql instances list --project="$PROJECT" --format="value(name)"); do
  run "delete Cloud SQL instance ${i}" gcloud sql instances delete "$i" --project="$PROJECT" --quiet --async
done
for k in $(names_of gcloud managed-kafka clusters list --location="$REGION" --project="$PROJECT" --format="value(name.basename())"); do
  run "delete Managed Kafka cluster ${k}" gcloud managed-kafka clusters delete "$k" --location="$REGION" --project="$PROJECT" --quiet --async
done
for s in $(names_of gcloud run services list --region="$REGION" --project="$PROJECT" --format="value(name)"); do
  run "delete Cloud Run service ${s}" gcloud run services delete "$s" --region="$REGION" --project="$PROJECT" --quiet --async
done
for v in $(names_of gcloud compute networks vpc-access connectors list --region="$REGION" --project="$PROJECT" --format="value(name.basename())"); do
  run "delete VPC connector ${v}" gcloud compute networks vpc-access connectors delete "$v" --region="$REGION" --project="$PROJECT" --quiet --async
done

if [ "$DRY_RUN" != "1" ]; then
  printf 'swept %s failures=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$failures" | gsutil -q cp - "gs://${TTL_BUCKET}/ttl/swept-at"
  say "sweep finished with ${failures} failure(s); Terraform state is now STALE — a plan will want to re-create what was swept"
fi
exit "$failures"
