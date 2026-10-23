# Run it: laptop or GCP

## Laptop: free, no account

Needs Java 17, Maven, Python 3, podman and podman-compose. Beam also needs `uv`, GnuPG and Node (Dataform).

```bash
make spec-test          # the comparator's own tests
make conformance-all    # every implementation vs. the oracle, row for row
make down-all           # stop every local stack
```

| Implementation | Run it |
|---|---|
| `beam-dataflow` | `make bootstrap && make up && make init-infra && make verify-stack && make run-initial && make verify` |
| `flink` | `make up && make run` (or `make cluster-submit`); `make conformance` |
| `flink-jdbc` | `make up && make run` (or `make cluster-submit`); `make generate ACCOUNTS=2000 RUN_ID=run-big && make lane RUN_ID=run-big`; `make conformance` |

`flink-jdbc` host ports: source Postgres 5443, lane Postgres 5442, Redpanda 19092 (also Beam's),
Flink UI 8082, mock 8099, kafka-ui 8089. Run `make down` before the next `make up`.

## GCP: billable, ask first

Nothing here runs without an explicit go-ahead.
The TTL guard (`ttl_hours=3`) is on by default. Apply first with `-var=ttl_dry_run=true`.
Read the sweeper's log. Then trust it with a delete.

### Credentials, all implementations

```bash
unset GOOGLE_APPLICATION_CREDENTIALS     # a stray key from another repo silently overrides ADC
gcloud auth application-default login
gcloud auth application-default set-quota-project <project>   # the project must have billing on
# 5-second ADC-vs-IAM test: 403 => ADC, 200 => look at IAM
curl -s -H "Authorization: Bearer $(gcloud auth application-default print-access-token)" \
  "https://serviceusage.googleapis.com/v1/projects/<project>/services?pageSize=1" | head -c 200
```

### beam-dataflow

1. New project, APIs, billing.
2. From `terraform/envs/dev`: bootstrap on **local state**. Then copy `backend.tf.example` to `backend.tf` and migrate state to the bucket. Then apply the rest.
   `TF_VAR_project_id` has no default: set it first.
3. Build all six images under one tag (context `implementations/`, `--build-context spec=<repo>/spec`).
4. Pre-stage the extract. Trigger the Composer DAG. Pass the Managed Kafka bootstrap override:
   `BOOT=$(gcloud managed-kafka clusters describe mig-kafka --location=europe-west1 --format='value(bootstrapAddress)')`.

### flink

From `terraform/envs/dev`, three applies:
- (a) `module.bootstrap`.
- (b) the infrastructure.
- (c) the Kafka bootstrap address, plus everything else.
  `BOOT=$(gcloud managed-kafka clusters describe mig-flink-kafka --location=europe-west1 --format='value(bootstrapAddress)')`.

Images: `gcp/docker/build.sh <registry> <tag>`.
One run = Cloud Run Job `mig-flink-orchestrator` → gate → three FlinkDeployments on GKE → recon.

### flink-jdbc

Flink's steps, plus three additions.
- Two Cloud SQL instances: `mig-flink-jdbc-src` and `mig-flink-jdbc-lane`.
- A source-seeder: `gcloud run jobs execute mig-flink-jdbc-source-seeder --region=europe-west1 --wait`
  with `--args=init|fixture,<run>|dump,<run>|count,<run>`.
- Gate exit codes: `3` = not ready (no attempt), `4` = terminal.

### Teardown: check every resource type

Plan to a file. Read it. Apply it. Delete the plan: it holds passwords.

Cloud SQL's peering refuses to delete for a while. Do not wait.
```bash
gcloud compute networks peerings delete servicenetworking-googleapis-com --network=mig-flink-vpc
terraform state rm 'module.cloudsql.google_service_networking_connection.this[0]'
```

Keep the state bucket: `terraform state rm module.bootstrap` first.

`flink-jdbc` needs four more `state rm`, before the destroy:
```bash
terraform state rm 'module.cloudsql.google_sql_user.app[0]' 'module.cloudsql.google_sql_database.mig[0]' \
  'module.cloudsql_source.google_sql_user.app[0]' 'module.cloudsql_source.google_sql_database.mig[0]'
```
Never match `sql_database` loosely. It also matches `google_sql_database_instance`.
Terraform then forgets the instances, and they keep billing.

Verify by resource type, not just compute:

```bash
gcloud container clusters list; gcloud sql instances list; gcloud managed-kafka clusters list --location=europe-west1
gcloud run services list; gcloud run jobs list; gcloud scheduler jobs list --location=europe-west1
gcloud compute networks list; gcloud services vpc-peerings list --network=mig-flink-vpc
gcloud secrets list; gcloud artifacts repositories list; gcloud storage buckets list
```

## What GCP taught us

- **Kafka can stall.** Managed Kafka sat in `CREATING` for over an hour. Terraform's wait ends at 60 minutes.
  Delete the cluster, then retry once.
- **Quota project.** Terraform's state backend needs an ADC quota project that has billing.
- **One project per implementation.** `flink` and `flink-jdbc` share `mig-flink-*` names. Beam shares the `<project>-landing` bucket.
  Parallel runs need one project each.
- **Beam builds late.** Its build scripts read Terraform outputs, so images come after the first apply.
  Turn the target-system mock off until the images exist.
- **Beam's credential check.** Its scripts refuse to run without `GOOGLE_APPLICATION_CREDENTIALS`.
  Point it at your own ADC file, `~/.config/gcloud/application_default_credentials.json`.
- **The `terraform import` command fails here.** It cannot evaluate the `count` expressions.
  Use a temporary `import {}` block with `-target=module.bootstrap`.
- **zsh traps.** Write `"${i}:${tag}"`, not `$i:tag`. Write `${=ON}` for a flag list.
- **Interrupt twice.** One Ctrl-C waits for in-flight creates, such as Kafka. A second cancels at once.
  Delete half-created resources with gcloud.
- **A new subnet may be invisible to Kafka.** On a fresh project, Managed Kafka returned 404 "subnetwork not found".
  Terraform had just created the subnet. Probably eventual consistency; unverified. Re-apply first.
- **A stale mock fails the run closed.** The Target System mock's Kafka login goes stale after about 34 min.
  `load` then reports `unsettled=N` and the run fails. Recycle the Cloud Run revision
  (update any env var, such as `RECYCLED_AT`) and retry. Evidence: [`docs/evidence/`](evidence/README.md).
- **Billing catalog needs its API.** Reading SKU prices fails with 403 until the Cloud Billing API is enabled on the project.
- **Run implementations one after another.** The recorded runs shared one project, so nothing ran in parallel.
