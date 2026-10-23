# GCP half: the DAG is green twice, after one stale-mock retry each

**Verdict: green.** The full migration DAG `etlr_beam_migration` ran on Cloud Composer 2 with real Dataflow, BigQuery,
Managed Kafka and Cloud Run, for 55 records and for 200,000. Project `<project>`, region `europe-west1`,
**one billing window: 12:56:44Z → 15:27:46Z (2 h 31 m 02 s)**.

- **55 records** (`run-gcp-fixture-20261022`): DAG 13:52:35 → 14:18:42, **26 min 07 s**. The oracle on the real BigQuery
  tables: **`ALL CONFORMANCE CHECKS PASSED (68 checks, profile staged)`** (`logs/C18-conformance-fixture.log`).
- **200,000 records** (`run-gcp-200k-20261022`): DAG 14:27:23 → 15:05:48, **38 min 25 s**.
  **`200,005 = 200,000 + 5`**, imbalance 0, **200,000 / 200,000 confirmed**, 0 orphan keys, ledgers agree.

The input is byte-identical to the laptop run's, which is what makes [`D4-local-vs-gcp.md`](D4-local-vs-gcp.md) a comparison.

## Per-task timing

| Task | 55 records | 200,000 records | What it does |
|---|---|---|---|
| `gate` | 2 s | 1 s | waits for `.FLG` |
| `intake` | 5 m 22 s | 5 m 04 s | Dataflow: decrypt, verify `.CHS`, parse door |
| `curate_run` | 32 s | 20 s | Dataform SQL |
| `enrich` | 4 m 20 s | 8 m 06 s | Dataflow: reference-data join |
| `produce` | 4 m 38 s | 10 m 59 s | Dataflow: map and schema doors, JSON batches of 200, Kafka |
| `load` | 1 m 24 s (+ failed attempt 2 m 19 s) | 2 m 06 s (+ failed attempt 3 m 17 s) | publish to Kafka, settle with the Target System |
| `recon` | 22 s | 31 s | rebuild the equation, confirm every row |
| `assert_run_balanced` | 7 s | 8 s | read the ledger |

Each Dataflow job takes about 4 minutes even for 55 records (start-up and shut-down). At 200,000 records
`intake` took no longer, `enrich` took 3 min 46 s longer and `produce` 6 min 21 s longer. Every job used one small worker (165 to 288 vCPU-seconds,
`dataflow-jobs-all.json`). **At 200,000 records Dataflow never scaled out.**

## What went wrong, honestly

1. **The image build script hung for about 10 minutes of billed time.** `make build-images` started
   `terraform output` in a background shell and it never returned, although the same command ran in 3 seconds
   in the foreground. Running the two build scripts directly worked. A second failure followed: the template
   script used gcloud's quota project, which I had pointed at a project with billing off, and got a 403.
   Fixing the quota project and rerunning finished the images at 13:26. Beam builds its images after the first apply
   (the scripts read Terraform outputs), so this time was billed. (`logs/C05-build-images.log`, `C05b-…`)
2. **The `load` task failed closed twice, both times on a stale mock.** The Target System mock holds a Kafka login
   that wedges after about 34 minutes. Attempt 1 published every document and settled none:
   `50 document(s) published but never settled`, then `200000 document(s) published but never settled`.
   The mock's own log shows `Connection to node -1 terminated during authentication` every second
   (`logs/C25-mock-wedge-evidence.log`). Recycling the mock (a new Cloud Run revision) fixed it each time.
   Airflow's retry then settled everything (50 and 200,000 documents, 0 unsettled). Most of the lost time was
   not the failed attempt but the retry delay: **6 min 21 s and 7 min 08 s** of waiting.
   Without the retries the DAG would have taken about 17 and 28 minutes.
   The first wedge was 41 minutes after the mock started. The second was 34.5 minutes after the first recycle.
3. **`local/scripts/dump_tables.py` stalled** inside the repo's BigQuery wrapper for over 7 minutes (a direct
   `google-cloud-bigquery` call took 3 seconds). The dump used in this pack goes straight through the client with
   the same cell format, so `compare.py` accepted it (`logs/C17-dump-tables-fixture.log`).
4. **A zone pin was needed.** `MIG_WORKER_ZONE=europe-west1-d` is set through Terraform, and the RBAC helper VM
   runs in `-d` too, because `europe-west1-b` had a stockout in an earlier session.

## What was not verified

- **The Kafka half of criterion 4 from a laptop.** Managed Kafka's bootstrap DNS resolves only inside Google's network.
  The Kafka side is proven in cloud instead: the loader report, the mock's counters
  (`kafka/B8-mock-final-stats.json`: 200,000 accepted, 523,390 duplicates, 200,000 distinct accounts)
  and the recon report.
- **Larger volume.** Dataflow used one worker at 200,000 records, so its autoscaling was not exercised.
- **Console screenshots.** Not captured (Google blocks automated sign-in).

## Teardown

Keep the state bucket → `plan -destroy` (75 resources) → the whole destroy took 18 min 36 s (Kafka 9 min 26 s,
the VPC connector 3 min 33 s) → Composer's own bucket was removed by hand (it is not in Terraform state) → state empty. Verified by resource type:
all **0** (`D5-teardown-verify.txt`). Only the default network and the state bucket remain.

## Files

| File | What it proves |
|---|---|
| `A0-INPUT-ACCOUNT.src` | the 55-record input, verbatim |
| `bq/A2 … B-*.tsv` | the seven tables of the fixture run, from real BigQuery |
| `logs/C24-bq-counts-200k.log` | row counts and the 5 reject reasons of the 200k run |
| `gcs/` | the staged bundle (`landing/`), the 50 target documents (`json-out/`), loader and extractor artefacts, recon reports for both runs |
| `kafka/B8-mock-final-stats.json` | the mock's counters after the 200k run |
| `B9-per-account-trace.tsv` | one row per source record (the Kafka columns are empty: see above) |
| `C16-…`, `C23-…` | the Airflow task states of both runs |
| `dataflow-jobs-all.json` | the 6 Dataflow jobs with their billed usage |
| `composer-env.json` | the Composer environment as deployed |
| `logs/C01 … C41` | every step in order |
| `logs/airflow/`, `logs/airflow-200k/` | full task logs, all 8 tasks, from Cloud Logging |
| `figures/` | F1 task timeline, F2 record flow, F3 the five rejects, F4 the load edge |
