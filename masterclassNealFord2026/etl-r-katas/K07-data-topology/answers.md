# Răspunsuri K07: Topologia de date

## Task 1: Evaluează topologia pentru fiecare implementare

| Implementare | Monolit | Domain | Dedicated | Motiv |
|---|---|---|---|---|
| **Flink (fișiere + Postgres)** | ★★★★★ (cost + simplitate) | ★★★ (consistentă) | ★ (cost mare, consistency work) | Fișierele TDS + Postgres schemas partajate prin Kubernetes |
| **Flink-jdbc** | ★ (cost) | ★★★★ (data consistency) | ★★★ (scalability) | Baza de date sursă dedicated + pipeline Postgres |
| **Beam Dataflow** | ★ (cost ~2.5×) | ★★★★★ (fault tolerance) | ★★ (scalability) | Composer + BigQuery + Managed Kafka |

**Concluzie:** Pentru deadline 6 luni + 200k students, **domain databases** câștigă pe balanța cost/consistență/scalabilitate. Dedicated e costisitor și necesită muncă de consistență; monolitul e prea simplu pentru 200k conexiuni concurente.

## Task 2: ADL-ul topologiei (template complet)

```
DESCRIPTION Define data topology for ETL-R
CATEGORY Data
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DATABASE account_source_db AS account_source
          WITHIN Flink Quantum OR FlinkJdbc Quantum OR Beam Quantum
          DEFINE SOURCE Tables: etlr_source.account, etlr_source.extract_control
     DEFINE DATABASE account_curated_db AS account_curated
          WITHIN Flink Quantum
          DEFINE TRANSFORMATION: parse → map → schema doors
     DEFINE DATABASE account_enriched_db AS account_enriched
          WITHIN Flink Quantum
          DEFINE JOIN: reference data (branches.json)
     DEFINE DATABASE account_target_db AS account_target
          WITHIN Flink Quantum OR FlinkJdbc Quantum OR Beam Quantum
          DEFINE TARGET: JSON document validation
     DEFINE DATABASE reject_log_db AS reject_log
          WITHIN Flink Quantum
          DEFINE REASONS: PARSE_*, MAP_*, SCHEMA_INVALID
     DEFINE DATABASE record_lineage_db AS record_lineage
          WITHIN Flink Quantum
          DEFINE SOURCE_KEY: raw:<hash> content name
     DEFINE DATABASE run_ledger_db AS run_ledger
          WITHIN Flink Quantum
          DEFINE TALLY: src_read = written + rejected
ASSERT(account_source_db is only accessed by Flink gate service AND FlinkJdbc intake service)
ASSERT(no quantum publishes a shared-database channel)
ASSERT(once the message broker is drained, Customer Database and Ticketing Database hold the same set of ticket keys)
```

## Task 3: Cine poate atinge reject_log / record_lineage

| Acces | Cine are voie | Ce se întâmplă dacă se încalcă |
|---|---|---|
| **Write** | `loader-app` (la intrare) și `recon-service` (după run) | Dacă altceva scrie direct (de exemplu, Reporting Service), `reject_log` devine neguvernabil + audit-ul e compromis |
| **Read** | Toate serviciile care necesită confirmări / raportare | Dacă Reporting citește `reject_log` direct, nu respectă quantum boundaries; trebuie să folosească MCP tools |

**Rezolvare:** Toate accesurile trebuie să fie declarate prin `ASSERT(...)`, nu accese directe. `reject_log` devine DLQ-ul sistemului cu reason-uri închise + cale umană (rectificarea sursei/mappingului).

## Task 4: `reject_log` ca DLQ complet

**Ce lipsește din rețeta completă "dead-letter + cale umană":**
1. **Reason enumerat** – de exemplu, `PARSE_BAD_NUMERIC`, nu text liber
2. **Calea umană** – `record_lineage` tracează originea fiecărei înregistrări plasate în DLQ
3. **Reconciliation fitness function** – `settle` contra confirmărilor, nu doar "lost count"
4. **Idempotența pe account key** – "o înregistrare trimisă de două ori e stocată o singură dată"

**Rețeta completă:**
- `reject_log` cu coloane: `run_id`, `account_key`, `reason`, `detail`
- `record_lineage` cu: `run_id`, `account_key`, `source_key` (raw:<hash>)
- Fitness function: `ASSERT(when the answer queue is drained, every answer receipt in Test Database has a graded answer in Grading Database)`
- Cale umană: dacă DLQ nu e gol după N minute, un operator revizuiește erorile manual.