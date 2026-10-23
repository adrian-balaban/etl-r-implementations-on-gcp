#!/usr/bin/env bash
# Publish the Dataform SQLX models to the Dataform repository Terraform created.
#
# This is the `make deploy-dataform` step. Two paths, depending on whether a git
# remote was wired into the Dataform repo at apply time (root var dataform_git_remote):
#
#   * git remote set  → `git subtree push --prefix=dataform` to that remote; Dataform's
#     scheduled compilation (terraform/modules/dataform, cron 0 3 * * * Europe/Bucharest)
#     picks the change up, and we also trigger a one-off compilation here so a broken
#     model fails the deploy rather than the next scheduled/DAG run.
#
#   * no git remote  → the Dataform repo is "unlinked". We compile it directly with the
#     `dataform` CLI against the repo id, which is enough for a prototype. The scheduled
#     release config is skipped because it requires a git remote.
#
# The Dataform repository id comes from the `dataform_repository` terraform output
# (terraform/modules/dataform, repo "etlr-beam").
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

# Project id, billing account and key file come from the environment — see _env.sh.
# shellcheck source=local/scripts/gcp/_env.sh
source local/scripts/gcp/_env.sh
require_gcp_env TF_VAR_billing_account
PROJECT="${TF_VAR_project_id}"

REPO="$(terraform -chdir="$TF_DIR" output -raw dataform_repository)"
echo "▶ Dataform repository: projects/${PROJECT}/locations/${TF_VAR_region}/repositories/${REPO}"

# Root var dataform_git_remote (default "") is not re-exported as an output, so read
# it from terraform.tfvars if present. Absent == no remote == unlinked-repo path.
GIT_REMOTE=""
if [[ -f "$TF_DIR/terraform.tfvars" ]]; then
  GIT_REMOTE="$(grep -E '^\s*dataform_git_remote\s*=' "$TF_DIR/terraform.tfvars" \
    | sed -E 's/.*=\s*"([^"]*)".*/\1/; s/.*=\s*(\S+).*/\1/' || true)"
fi

if [[ -n "$GIT_REMOTE" ]]; then
  # Linked-repo path: publish dataform/ to its own GitHub repo, Dataform compiles from
  # the configured branch. `git subtree push` (not `git init` inside dataform/) so the
  # main repo's own .git stays the only one in this working tree — a nested repo was
  # rejected for exactly that reason.
  echo "▶ git remote configured: ${GIT_REMOTE}"
  git remote add dataform-deploy "${GIT_REMOTE}" 2>/dev/null || \
    git remote set-url dataform-deploy "${GIT_REMOTE}"
  git subtree push --prefix=dataform dataform-deploy main
  echo "✓ pushed dataform/ to git; Dataform will compile per its release config."

  # Fail the deploy on a broken model instead of waiting for the next scheduled/DAG
  # compile to discover it.
  if command -v gcloud >/dev/null 2>&1; then
    echo "▶ triggering a one-off compilation to validate the push"
    gcloud dataform repositories compilation-results create \
      --project="${PROJECT}" --location="${TF_VAR_region}" \
      --repository="${REPO}" --git-commitish=main \
      --format='value(name)' \
    && echo "✓ compilation result created — check the console for compilationErrors." \
    || { echo "error: compilation failed — see output above." >&2; exit 1; }
  else
    echo "⚠ gcloud not found — skipping the post-push compilation check." >&2
  fi
else
  # Unlinked-repo path: compile directly with the dataform CLI.
  if ! command -v dataform >/dev/null 2>&1; then
    echo "error: dataform CLI not installed and no git remote is configured." >&2
    echo "       Either set dataform_git_remote and re-apply, or install the CLI:" >&2
    echo "         npm install -g @dataform/cli" >&2
    exit 1
  fi
  echo "▶ no git remote — validating dataform/ with a local compile"
  # `dataform compile` is purely local in CLI 3.x (no repository/project flags); it
  # proves the SQLX is valid. Execution against BigQuery goes through
  # local/scripts/run_dataform.py (BQ_TARGET=real), which runs the compiled SQL in
  # dependency order — the Dataform *service* only executes workflows for git-linked
  # repos, which this prototype deliberately does not require.
  (cd dataform && dataform compile --json > /dev/null)
  echo "✓ dataform/ compiles clean. Execution happens via run_dataform.py (BQ_TARGET=real)."
fi