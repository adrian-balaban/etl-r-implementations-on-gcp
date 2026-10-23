# Same input, same decisions, different infrastructure

The laptop run (`run-local-20261022`) and the GCP run (`run-gcp-fixture-20261022`) read a **byte-identical** input
(sha256 `86f76332…`). So the comparison is exact.

## What is identical

Rows compared table by table, ignoring only the run id and wall-clock columns:

| Table | Local rows | GCP rows | Identical rows |
|---|---|---|---|
| `account_src` | 52 | 52 | **52** |
| `account_curated` | 52 | 52 | **52** |
| `account_enriched` | 52 | 52 | **52** |
| `account_target` (JSON documents) | 50 | 50 | **50** |
| `record_lineage` | 5 | 5 | **5** |
| `run_ledger` | 1 | 1 | **1** |
| `reject_log` | 5 | 5 | 3, and 2 that differ only by the `run_id` **inside** the stored snapshot |

The two exceptions are the map and schema rejects. Their `raw_record` is a JSON snapshot of the enriched row,
and that snapshot contains the run id. Nothing else differs. The same holds for the report: `balancingEquation`,
`targetSystemReconciliation`, `transformationLoadReconciliation` and `ledgerAgreement` are the same numbers in both.

**The same five records are rejected, at the same stage, with the same reason, by the same component, with the same
`account_key` hashes.** Both runs pass the oracle: 68 / 68.

## What differs, and why

| | Laptop | GCP | Why |
|---|---|---|---|
| Wall clock | 25 s | 26 min 07 s | Composer, one Dataflow job per stage (~4 min each), pod start-up |
| Orchestration | one Python script | Airflow DAG, 8 tasks, `KubernetesPodOperator` | Composer is the managed scheduler |
| Load split | 0 accepted, 50 duplicate | 13 accepted, 37 duplicate | a race between producer and loader on a mock that already holds some keys. The mock's verdicts add up to the same 50 documents. |
| Failures | none | `load` failed closed once, then settled | a stale Kafka login in the mock (`README.md`) |
| Kafka visible from the laptop | yes | no | Managed Kafka's DNS resolves only inside Google's network. The mock's counters stand in. |
| Cost | $0 | about $2.33 for the whole session | `../../cost-estimate.md` |

## And at scale

The 200,000-record run holds the same shape: the five defects at the same two doors, `200,005 = 200,000 + 5`,
and every row confirmed (`gcs/recon-200k/`, `logs/C24-bq-counts-200k.log`).
