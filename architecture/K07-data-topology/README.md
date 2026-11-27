# K07. The data topology

## Scenario (5 min)
Each implementation lays its table of 7 types into equivalent storage: PostgreSQL schemas (Flink), BigQuery datasets (Beam), or tables in Postgres schemas (Flink-jdbc). Which topology is the right one?

## Source material
- `PIPELINE.md` (shared names: account_src, account_curated, account_enriched, account_target, reject_log, record_lineage, run_ledger)
- `docs/diagrams/flink/erd-postgres.svg`, `docs/diagrams/beam-dataflow/erd-bigquery.svg`
- `docs/diagrams/08-stage-mapping.svg` (canonical stage → each implementation's artefact)
- `spec/PIPELINE.md` (the source system as a database, for flink-jdbc)
- `docs/evidence/README.md` (what the numbers say)

## Tasks (10 min, individual-discussion-team rounds)

1. **Evaluate monolith / domain / dedicated** for each implementation on cost, simplicity, consistency, scalability, fault tolerance, quantum.
2. **The topology ADL:** `DEFINE DATABASE`/`SCHEMA` + access assertions, without nesting.
3. **Who may touch `reject_log`/`record_lineage`** + what the `loader-app` vs. `recon-service` access does.
4. `reject_log` as the DLQ equivalent with an enumerated reason — what is missing from the full "dead-letter + human path" recipe?

## ADL deliverable (starting point)

```
DESCRIPTION Define data topology for ETL-R
CATEGORY Data
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DATABASE account_source_db AS account_source
          WITHIN Flink Quantum OR FlinkJdbc Quantum OR Beam Quantum
     DEFINE DATABASE account_curated_db AS account_curated
          WITHIN Flink Quantum
     DEFINE DATABASE account_enriched_db AS account_enriched
          WITHIN Flink Quantum
     DEFINE DATABASE account_target_db AS account_target
          WITHIN Flink Quantum OR FlinkJdbc Quantum OR Beam Quantum
     DEFINE DATABASE reject_log_db AS reject_log
          WITHIN Flink Quantum
     DEFINE DATABASE record_lineage_db AS record_lineage
          WITHIN Flink Quantum
     DEFINE DATABASE run_ledger_db AS run_ledger
          WITHIN Flink Quantum
ASSERT(account_source_db is only accessed by Flink gate service AND FlinkJdbc intake service)
ASSERT(no quantum publishes a shared-database channel)
```