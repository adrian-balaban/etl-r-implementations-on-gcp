# beam-dataflow on GCP: every record, every hop, on a laptop and in the cloud

The deep-dive pack for the `beam-dataflow` implementation, 2026-10-22, from commit `136a32f`.
It shows *how* the pipeline decides, not only *that* it balances: the input file, the decision for each record,
the data at every hop, the full logs, and the same input run twice, on a laptop and on GCP.

## Status

| Half | State |
|---|---|
| **Local, end to end** | ✅ acceptance **10 / 10**, oracle **68 / 68**, unit tests green (33 Python, Java suites) |
| **GCP, 55 records** | ✅ DAG green, oracle **68 / 68** on the real BigQuery tables. One retry of the `load` task (a stale mock). |
| **GCP, 200,000 records** | ✅ DAG green: `200,005 = 200,000 + 5`, **200,000 / 200,000 confirmed**. One retry of the `load` task (a stale mock). |

## The runs

| Run | Records | Result |
|---|---|---|
| `run-local-20261022` (laptop stack) | 55 | 50 migrated, 5 rejected, 50 / 50 confirmed. `make verify`: 10 / 10. |
| `run-gcp-fixture-20261022` (Cloud Composer) | 55, byte-identical input | 50 migrated, 5 rejected, 50 / 50 confirmed. DAG 26 min 07 s. |
| `run-gcp-200k-20261022` (Cloud Composer) | 200,005 | 200,000 migrated, 5 rejected, 200,000 / 200,000 confirmed. DAG 38 min 25 s. |

The 55-record input has sha256 `86f76332…` on the laptop and on GCP. It is also the oracle's input, so the two runs
are a real comparison. The result is in [`gcp/D4-local-vs-gcp.md`](gcp/D4-local-vs-gcp.md): **identical rows in every table**
(only the `run_id` embedded in two reject snapshots differs), the same five records rejected at the same stage with the
same reason and the same `account_key` hashes.

```
 ✓  1. balancing equation closes: 55 = 50 migrated + 5 not migrated
 ✓  2. seeded malformed records rejected with correct reason codes (all 5)
 ✓  3. every TARGET document validates against the JSON Schema: 50/50
 ✓  4. TARGET emitted in 200-element batches: 1 batch (1 GCS file, 100 Kafka messages)
 ✓  5. every key appears exactly once in TARGET: 50 keys, 0 orphans
 ✓  6. every not-migrated record is named in record_lineage: 5
 ✓  7. all five artefact types present in both lanes
 ✓  8. .CHS checksums verify on both sides
 ✓  9. every TARGET row confirmed by Target System: 50/50
 ✓ 10. every published document settled: 50 published, 0 confirmed, 50 duplicate, 0 rejected, 0 unsettled
```

(`local/21-verify.log`. The split of 50 "duplicate" is the producer and the loader both publishing the same keyed
documents. The mock records exactly 50 `created` and 50 `duplicate` across 100 messages.)

## What this run adds

- **A timed, priced run.** Ramp-up 50 min 56 s, ramp-down 18 min 36 s, 2 h 31 m of GCP time,
  75 Terraform resources, estimated cost $2.33. See [`../README.md`](../README.md) and [`../cost-estimate.md`](../cost-estimate.md).
- **A failure recorded as it happened.** The `load` task failed closed twice (once per run) because the
  Target System mock's Kafka login had gone stale. Airflow retried, the mock was recycled, and the retry settled
  every document. Nothing was lost silently: the documents were counted `unsettled` and the task failed.
  See [`gcp/README.md`](gcp/README.md) and `gcp/figures/F4-load-edge.svg`.

## Files

### A: record-level decisions

| File | What it shows |
|---|---|
| `local/data/00-INPUT-ACCOUNT.src`, `01-INPUT-annotated.md` | the input file, verbatim, and the 5 seeded defects with exact line numbers |
| `local/bq/A2-reject_log.tsv`, `gcp/bq/A2-reject_log.tsv` | the 5 rejects with stage, reason, the system's own detail and the raw record |
| `…/A3-record_lineage.tsv` | the same 5, keyed by door, stage and reason |
| `…/A4-run_ledger.tsv` | the intake door's tally (`55 / 52 / 3`) |

### B: data at every hop

| File | Rows |
|---|---|
| `…/B-etlr_extraction-account_src.tsv` | 52 |
| `…/B-etlr_transformation-account_curated.tsv`, `…-account_enriched.tsv` | 52, 52 |
| `…/B-etlr_transformation-account_target.tsv` | 50 |
| `local/gcs/`, `gcp/gcs/` | the encrypted bundle, the 50 target documents (JSON lines), loader and extractor `.RPT / .CHS / .ERR / .FLG`, the recon report |
| `local/kafka/B6-*.tsv` | 100 target messages and 100 confirmations (50 `created`, 50 `duplicate`) |
| `…/B8-mock-final-stats.json` | the mock's own counters |
| **`…/B9-per-account-trace.tsv`** | **one row per source record**: presence at each hop, its Kafka messages, the verdicts, the deciding system |

### C: logs

`local/10 … 99`: unit tests, stack up, init, the run, verify, dump, conformance. `gcp/logs/C01 … C41`: every GCP step in order,
including the three failed attempts. `gcp/logs/airflow/` and `airflow-200k/`: **full task logs for all 8 tasks, both runs.**

### D: systems and narrative

| File | What it is |
|---|---|
| `local/D1-systems-and-decisions.md` | every system in the chain, what it reads and writes, and which decisions it may take |
| `local/D2-walkthrough.md` | one migrating record and one rejected record, hop by hop, with the actual bytes |
| `gcp/D3-infra-inventory.txt` | Composer, Kafka, Cloud Run and the rest, as deployed |
| `gcp/D4-local-vs-gcp.md` | the comparison: same input, same decisions, different infrastructure |
| `gcp/D5-teardown-verify.txt` | resource inventory by type after the teardown |
| `gcp/figures/` | four figures drawn **from the captured data** (see its README) |

Google Cloud console screenshots are not part of this pack: Google blocks sign-in inside an automated browser.
The verifiable evidence is the data, which can be re-checked.

Identifiers (project id and number, account, email, service URLs, bucket hashes) are replaced with placeholders.
