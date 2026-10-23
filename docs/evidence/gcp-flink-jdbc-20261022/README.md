# flink-jdbc on GCP: the source is a database, and a dead mock fails closed

One real GCP run of the `flink-jdbc` implementation, 2026-10-22, from commit `136a32f`.
Project `<project>`, region `europe-west1`, **one billing window: 11:53:21Z → 12:50:06Z (56 min 45 s)**.
Everything but the Terraform state bucket was torn down at the end and checked by resource type.

Images were checked in the registry by **config id**: `orchestrator 77a09df2a406`, `flink-jdbc-job b4f3a8a323c3`,
`source-seeder ddd99e90e051`, `target-system-mock 63299ee9be76` (`logs/05-push-images.log`).

Here the Source System is a Postgres database (Cloud SQL, private IP). There is no bundle and no `.FLG`:
a **control row** (`row_count`, `content_digest`) vouches for the source, and the gate checks it.

## Results

| # | Scenario | Result | Where |
|---|---|---|---|
| 1 | Fixture, run `run-fixture-20261022` | The typed database refuses 2 of the 55 rows (`ACC000900001` bad numeric, `ACC000900002` bad date). **`src_read=53 written=50 rejected=3`, balanced**, 1 batch of 50, **50 / 50 confirmed**, all stage equations hold. | `recon/`, `logs/orchestrator-full.log` |
| 2 | **Oracle, on the dump of run 1** | **`ALL CONFORMANCE CHECKS PASSED (46 checks, profile staged)`** against the typed-source oracle. | `logs/12-conformance-fixture.log`, `sql/` |
| 3 | **The source vouches, and GCP agrees** | `source_read`: `row_count 53`, `content_digest 31503124396361923406`, **the value pinned in `spec/PIPELINE.md`**. `recon-jdbc`: "read exactly what the source vouched for". | `sql/run-fixture-20261022/source_read.tsv` |
| 4 | **200,000 accounts**, run `run-200k-20261022` | The seeder generated 200,005 records inside GCP. The database refused 2, so **`src_read=200003 written=200000 rejected=3`**, balanced. **1,000 batches of 200. 200,000 / 200,000 confirmed.** Stages: `200003=200003`, `200002=200002` ×3 | `recon/`, `logs/orchestrator-full.log` |
| 5 | **Failure, then recovery** | Fixture attempt 1 balanced but **failed closed at settle: 0 of 50 confirmed.** The orchestrator retried; after the mock was recycled, attempt 2 passed. See below. | `logs/10-mock-recycle.log` |
| 6 | Lock under an every-minute scheduler | 19 executions started, **10 backed off** on the lock. | `logs/orchestrator-full.log` |

## The failed first attempt

All stages ran correctly on attempt 1 (`53 = 50 + 3`, every stage equation `ok`). Then, at 12:15:36:

```
recon: target rows=50 confirmed=0 unconfirmed=50
RECONCILIATION FAILED: unconfirmed account keys: [...]
orchestrator: run run-fixture-20261022 did not complete this firing
```

The run produced the right documents, but nothing confirmed them, so it did not count as done. The cause was in
the Target System mock: its Kafka consumer stopped two seconds after its first start
(`consumer loop stopped: Authentication failed … OAUTHBEARER`, 12:06:10), while the process stayed up.
The runbook's remedy is a new revision of the mock. It was applied at 12:18:32, the consumer started cleanly,
and attempt 2 (12:15:51 → 12:20:03) confirmed 50 / 50. **The cause of the first-start failure is not established.**

## Timeline

| Step | Time (UTC) | Duration |
|---|---|---|
| Billing window starts: `terraform apply`, stage (b), 59 resources | 11:53:21 | |
| Managed Kafka `ACTIVE` | ≈ 12:04 | ≈ 10 min |
| Stage (c), 22 resources; seeder `init` | 12:06 – 12:07 | 2 min |
| **Fixture loaded into the source: ramp-up ends** | 12:07:46 | **14 min 25 s** |
| Fixture run, both attempts: discovered 12:08:51 → done marker 12:20:03 | | 11 min 12 s |
| 200k: seeder generates and loads | 12:22:10 – 12:26:30 | 4 min 20 s |
| 200k run: discovered 12:26:53 → done marker 12:32:53 | | **5 min 59 s** (intake 1m42, curate 1m31, produce 2m03) |
| Teardown starts | 12:35:26 | |
| **Last resource gone: billing window ends** | 12:50:06 | **ramp-down 14 min 40 s** |

## Footprint

**81 Terraform-managed resources** (59 + 22; the state bucket and 16 API enablements are excluded), 34 resource types,
9 GCP services. The difference from `flink` is a second Cloud SQL instance (the source), a seeder job and
the source database's secrets. Sizes as deployed: `infra/inventory.txt`. Estimated cost **$0.94**:
[`../cost-estimate.md`](../cost-estimate.md).

## What the run showed

- **A typed source refuses what a file would carry.** Two fixture rows never reach the pipeline: the database
  refuses them at load. That is why the numbers are `53 = 50 + 3`, not `55 = 50 + 5`.
- **The teardown worked in one pass.** The four database user and database addresses were removed from
  state first (exact addresses, never the instances). Only the documented peering refusal remained
  (`logs/20-teardown-destroy.log`).
- **The seeder dump appears late.** Files written through the mounted bucket became visible about 15 seconds
  after the job finished.

## What was not verified

- **The mock's own counters** (internal ingress), **PGP**, and the **TTL guard's real mode**
  (armed for 3 h, expiry 15:06:00Z, never fired).
- **Why the mock's consumer died at its first start.**
- **Volume above 200,000.**

## Teardown

Keep the state bucket → remove the four database addresses from state → `plan -destroy` (77 resources) → peering
fallback → state empty. Verified by resource type: all **0**; only the default network and the state bucket remain
(`logs/21-teardown-verify.log`).

## Files

| Path | What it is |
|---|---|
| `logs/01 … 21` | every step in order |
| `logs/orchestrator-full.log` | the orchestrator, seeder and recon log lines for both runs |
| `recon/` | the recon reports, JSON and HTML, for both runs |
| `sql/run-fixture-20261022/` | the seven lane tables of the fixture run and `source_read.tsv` |
| `infra/` | deployed sizes and the billing window |

Identifiers (project id and number, account, email, service URLs) are replaced with placeholders.
