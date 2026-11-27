# K07 answers: The data topology

## Task 1: Evaluate the topology for each implementation

| Implementation | Monolith | Domain | Dedicated | Reason |
|---|---|---|---|---|
| **Flink (files + Postgres)** | ★★★★★ (cost + simplicity) | ★★★ (consistency) | ★ (high cost, consistency work) | TDS files + Postgres schemas shared through Kubernetes |
| **Flink-jdbc** | ★ (cost) | ★★★★ (data consistency) | ★★★ (scalability) | a dedicated source database + a pipeline Postgres |
| **Beam Dataflow** | ★ (cost ~2.5×) | ★★★★★ (fault tolerance) | ★★ (scalability) | Composer + BigQuery + Managed Kafka |

**Conclusion:** for a 6-month deadline + 200k students, **domain databases** win on the cost/consistency/scalability balance. Dedicated is expensive and needs consistency work; the monolith is too simple for 200k concurrent connections.

## Task 2: The topology ADL (complete template)

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

## Task 3: Who may touch reject_log / record_lineage

| Access | Who is allowed | What happens if violated |
|---|---|---|
| **Write** | `loader-app` (at intake) and `recon-service` (after the run) | if anything else writes directly (say, a Reporting Service), `reject_log` becomes ungovernable and the audit is compromised |
| **Read** | every service that needs confirmations / reporting | if Reporting reads `reject_log` directly, it breaks the quantum boundaries; it must go through the MCP tools |

**The resolution:** every access must be declared through `ASSERT(...)`, never taken directly. `reject_log` becomes the system's DLQ with closed reasons + a human path (fix the source or the mapping).

## Task 4: `reject_log` as a complete DLQ

**What is missing from the full "dead-letter + human path" recipe:**
1. **An enumerated reason** — e.g. `PARSE_BAD_NUMERIC`, not free text
2. **The human path** — `record_lineage` traces the origin of every record placed in the DLQ
3. **A reconciliation fitness function** — `settle` against the confirmations, not just a "lost count"
4. **Idempotency on the account key** — "a record sent twice is stored once"

**The complete recipe:**
- `reject_log` with columns: `run_id`, `account_key`, `reason`, `detail`
- `record_lineage` with: `run_id`, `account_key`, `source_key` (raw:<hash>)
- The fitness function: `ASSERT(when the answer queue is drained, every answer receipt in Test Database has a graded answer in Grading Database)`
- The human path: if the DLQ is not empty after N minutes, an operator reviews the errors manually