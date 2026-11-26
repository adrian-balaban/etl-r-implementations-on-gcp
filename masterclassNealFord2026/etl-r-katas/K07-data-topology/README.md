# K07. Topologia de date

## Scenariu (5 min)
Fiecare implementare așază tabelul său de 7 tipuri în stocare echivalentă: PostgreSQL schemas (Flink), datasets BigQuery (Beam), sau tables în Postgres schemas (Flink-jdbc). Care topologie e corectă?

## Material sursă
- `PIPELINE.md` (names shared: account_src, account_curated, account_enriched, account_target, reject_log, record_lineage, run_ledger)
- `docs/diagrams/flink/erd-postgres.svg`, `docs/diagrams/beam-dataflow/erd-bigquery.svg`
- `docs/diagrams/08-stage-mapping.svg` (canonical stage → each implementation's artefact)
- `spec/PIPELINE.md` (source system as database for flink-jdbc)
- `docs/evidence/README.md` (what the numbers say)

## Task-uri (10 min, runde individual-discussion-team)

1. **Evaluează monolit / domain / dedicated** pentru fiecare implementare pe cost, simplitate, consistență, scalabilitate, fault tolerance, cuantă.
2. **ADL-ul topologiei:** `DEFINE DATABASE`/`SCHEMA` + aserțiuni de acces, fără imbricare.
3. **Cine poate atinge `reject_log`/`record_lineage`** + ce fac cu accesul `loader-app` vs. `recon-service`.
4. `reject_log` echivalentul DLQ cu reason enumerat – ce lipsește din rețeta completă "dead-letter + cale umană"?

## Livrabil ADL (punct de start)

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