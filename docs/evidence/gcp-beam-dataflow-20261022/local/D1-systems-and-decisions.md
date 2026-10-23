# Who decides what

Eleven systems touch a record. Only two stages may reject one, and only three components can fail a run.

| # | System | Reads | Writes | Decides |
|---|---|---|---|---|
| 1 | **Extractor App** (Java) | the input file | `.DAT` parts, `.RPT`, `.CHS`, `.ERR`; the encrypted bundle `ACCOUNT.tar.gz.pgp` and, last, `.FLG` | whether a row is unreadable *as a row* (`.ERR`). Here: 0. |
| 2 | **gate** (Airflow sensor) | the landing bucket | nothing | when to start: only when `.FLG` exists |
| 3 | **intake** (Beam on Dataflow) | the bundle | `account_src`, `reject_log`, `record_lineage`, `run_ledger` | **door 1, parse.** Decrypts, verifies `.CHS`, walks the fields in TDS order. |
| 4 | **curate_run** (Dataform SQL) | `account_src` | `account_curated` | nothing: types and derives columns |
| 5 | **enrich** (Beam) | `account_curated`, reference data | `account_enriched` | nothing: adds columns, never drops a row |
| 6 | **produce** (Beam) | `account_enriched` | `account_target`, `reject_log`, `record_lineage`, JSON batches of 200, Kafka | **door 2, map and schema.** |
| 7 | **load** (Loader App, Java) | the JSON batches | Kafka target topic; `.RPT`, `.CHS`, `.ERR`, `.FLG` | nothing about the data. **Fails the task if any document is unsettled.** |
| 8 | **Target System** (the mock) | the target topic | confirmations and rejections | accepts a document once (`created`) and answers `duplicate` after that |
| 9 | **recon** (Java) | `reject_log`, `account_target`, the confirmations | the reconciliation report | whether the whole-lane equation closes and every row is confirmed |
| 10 | **assert_run_balanced** (Airflow) | `run_ledger` | nothing | fails the DAG if the ledger does not balance |
| 11 | **Orchestrator** (Composer, or the laptop script) | everything above | the run state | the order of the stages and the retries |

## The five rejects

| Input line | Record | Stage | Reason | Decided by | The system's own detail |
|---|---|---|---|---|---|
| 11 | `ACC000900000` | parse | `PARSE_BAD_COLUMN_COUNT` | intake | `CURRENCY needs column 4, row has 4` |
| 29 | `ACC000900001` | parse | `PARSE_BAD_NUMERIC` | intake | `BALANCE='+0000000ABCD.56'` |
| 56 | `ACC000900002` | parse | `PARSE_BAD_DATE` | intake | `OPEN_DATE='20191345'` |
| 33 | `ACC000900003` | map | `MAP_UNMAPPED_ENUM_VALUE` | produce | `status: 'X' not in ['A', 'C', 'D']` |
| 27 | `acc-bad!0001` | schema | `SCHEMA_INVALID` | produce | `accountId: 'acc-bad!0001' does not match '^[A-Z0-9]+$'` |

Three records leave at intake and two at produce. No other system rejects anything.

## Why the two ledgers differ

`run_ledger` is written by **intake**, so it holds the intake door's tally: `55 read / 52 written / 3 rejected`.
The whole-lane equation `55 = 50 + 5` is rebuilt by **recon** from `reject_log` (5) and `account_target` (50).
The report's `ledgerAgreement` section checks that `reject_log` and `record_lineage` both name the same 5.
Both views balance. They answer different questions: "did intake account for what it read?" and "did the lane?".

## What decides a failure

Three components can fail a run, and each fails closed:
**load** (a published document nobody confirmed), **recon** (an unconfirmed target row or an unbalanced equation)
and **assert_run_balanced** (the ledger). The two GCP runs met the first one: see `../gcp/README.md`.
