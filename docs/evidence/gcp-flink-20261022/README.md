# flink on GCP: 55 records, then 200,000, in one hour

One real GCP run of the `flink` implementation, 2026-10-22, from commit `136a32f`.
Project `<project>`, region `europe-west1`, **one billing window: 10:43:43Z → 11:43:14Z (59 min 31 s)**.
Everything but the Terraform state bucket was torn down at the end and checked by resource type.

Images were built from the commit and checked in the registry by **config id**, not by tag:
`orchestrator 7b433fad6c5c`, `flink-job 0d939c2980fc`, `target-system-mock 63299ee9be76` (`logs/05-push-images.log`).

## Results

| # | Scenario | Result | Where |
|---|---|---|---|
| 1 | Fixture, run `run-fixture-20261022` (55 records) | `src_read=55 written=50 rejected=5`, balanced. 1 batch of 50. **50 / 50 confirmed.** All four stage equations hold. | `recon/`, `logs/orchestrator-full.log` |
| 2 | **Oracle, on the Cloud SQL dump of run 1** | **`ALL CONFORMANCE CHECKS PASSED (68 checks, profile staged)`**: every table row for row, ledger shape = whole lane `55 / 50 / 5` | `logs/10-conformance-fixture.log`, `sql/` |
| 3 | **200,000 accounts**, run `run-200k-20261022` | `src_read=200005 written=200000 rejected=5`, balanced. **1,000 batches of 200. 200,000 / 200,000 confirmed.** Stages: `200005=200005`, `200002=200002` ×3 | `recon/`, `logs/12-db-counts.log` |
| 4 | Lock under an every-minute scheduler | 16 orchestrator executions started, **11 backed off** with `another execution holds … exiting cleanly`. No run started twice. | `logs/orchestrator-full.log` |

The same five seeded defects were rejected at both scales: `PARSE_BAD_COLUMN_COUNT`, `PARSE_BAD_NUMERIC`,
`PARSE_BAD_DATE` (parse door), `MAP_UNMAPPED_ENUM_VALUE`, `SCHEMA_INVALID` (map and schema doors).

## Timeline

| Step | Time (UTC) | Duration |
|---|---|---|
| Billing window starts: `terraform apply`, stage (b), 50 resources | 10:43:43 | |
| Managed Kafka `ACTIVE` | ≈ 11:04 | ≈ 20 min (the critical path) |
| Stage (c), 21 resources, then schema from a pod on the VPC | 11:06 – 11:07 | 3 min |
| **First input staged: ramp-up ends** | 11:07:19 | **23 min 36 s** |
| Fixture run: discovered 11:09:47 → done marker 11:15:40 | | 5 min 53 s |
| 200k run: discovered 11:17:48 → done marker 11:24:43 | | **6 min 55 s** (intake 1m52, curate 1m52, produce 2m23) |
| Teardown starts | 11:26:18 | |
| **Last resource gone: billing window ends** | 11:43:14 | **ramp-down 16 min 56 s** |

## Footprint

**71 Terraform-managed resources** (50 + 21; the state bucket and 16 API enablements are excluded),
34 resource types, 9 GCP services: GKE, Compute (network, NAT, VPC connector), Cloud SQL, Managed Kafka,
Cloud Run (the mock and two jobs), Cloud Scheduler, Artifact Registry, Cloud Storage, Secret Manager.
Sizes as deployed: `infra/inventory.txt`. Estimated cost **$0.90**: [`../cost-estimate.md`](../cost-estimate.md).

## What the run showed

- **Order matters: schema first.** The orchestrator races a bundle that arrives before the schema.
  The schema was applied before any input was staged (`logs/08-schema-init.log`).
- **Teardown needed three passes.** The first `terraform apply -destroy` stopped on the database role
  (`role "mig" cannot be dropped…`) and left Cloud SQL running. Removing exactly `google_sql_user.app[0]`
  from state, then destroying again, deleted the instance. Cloud SQL's network peering then refused to delete
  and was removed directly. The steps are now in the runbook (`logs/20-teardown-destroy.log`).
- **The lane had no image build script.** `implementations/flink/gcp/docker/build.sh` did not exist.
  It was written for this run and committed.
- **Two shell traps cost a retry each:** an unquoted `$ON` is one word in zsh, and `python3 -I -m harness.bundle`
  cannot import `harness` (isolated mode drops the working directory).
- **No stall this time.** Kafka took about 20 minutes. Earlier the same day a Kafka create in a different project
  stayed in `CREATING` for over an hour and was deleted and retried.

## What was not verified

- **The mock's own counters.** `/__admin/stats` answers only inside the VPC. The confirmation side is proven by
  recon instead: the settle loop read 200,000 distinct confirmations and exited 0.
- **PGP-encrypted delivery** and the **TTL guard's real mode.** The guard was armed (3 h, expiry 14:05:49Z) and
  never fired, because the teardown ran first.
- **Volume above 200,000.**

## Teardown

Order: keep the state bucket (`terraform state rm module.bootstrap`) → `plan -destroy` (71 resources) →
three passes (above) → state empty. Verified by resource type: GKE, Cloud SQL, Managed Kafka, Cloud Run,
Scheduler, VPC connectors, routers, addresses, peerings, secrets, registry, service accounts, VMs and disks
are all **0**. Only the default network and the state bucket remain (`logs/21-teardown-verify.log`).

## Files

| Path | What it is |
|---|---|
| `logs/01 … 21` | every step in order: bootstrap, image build and push, plans, applies, schema, staging, conformance, counts, teardown, verification |
| `logs/orchestrator-full.log` | the Cloud Run orchestrator's log for both runs |
| `recon/` | the recon reports, JSON and HTML, for both runs |
| `sql/run-fixture-20261022/` | the seven tables of the fixture run, as dumped from Cloud SQL |
| `infra/` | deployed sizes, the landing bucket layout, the billing window |

Identifiers (project id and number, account, email, service URLs) are replaced with placeholders.
