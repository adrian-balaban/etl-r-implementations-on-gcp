# K09 answers: The business environment and cost

## Task 1: The response to each scenario

| Scenario | Flink | Flink-jdbc | Beam Dataflow | The risk |
|---|---|---|---|---|
| **S1: a new project every week (DEPOSIT)** | ⚠️ a mapping change + conformance revalidation | ✅ pin the mapping + run `verify-project2` | ⚠️ new mappings + run beam generate | Risk: proving `project2` ≡ `project1`; `project2` must not die — nor is it allowed to |
| **S2: GCP changes prices or degrades Managed Kafka** | ⚡ ramp-up ±10 min (Kafka variance) | ⚡ ramp-up ±10 min (Kafka variance) | ⚡⚡ ramp-up ±20 min (Composer + Kafka) | Risk: runtime costs grow; watch with the MCP tool `get_cost_last_run()` |
| **S3: 20M records per run** | ✅ every job has a fixed start-up cost | ✅ at least the fixed costs are smaller | ❌ Composer $1.03 fixed + 2.5× costs | Risk: Beam becomes 5× more expensive; move to Flink for large volumes |
| **S4: a Target System API v2 for confirmations** | ⚠️ change the schema + revalidate conformance | ⚠️ change the source database + migrate | ⚠️ change the Kafka contract + redeploy | Risk: `SCHEMA_INVALID` at load; `MAP_UNMAPPED_ENUM_VALUE` at map; watch the contract |
| **S5: another region/residency** | ⚠️ replicate the GCP infrastructure | ✅ the Postgres sources can be local | ⚠️ Composer + BigQuery multi-region | Risk: cross-region latency; use BigQuery multi-region |
| **S6: a fourth implementation is requested (Spark/Dataproc)** | not natively supported | not natively supported | ⚠️ can be added as a beam variant | Risk: it is not Architecture as Code — it is a "new tool" without a spec |

**Conclusion:** for most scenarios, **Flink** and **Flink-jdbc** are the defensive choice; Beam is justified only for small workloads, or when cost is not the deciding factor.

## Task 2: The cost ADL on 2 $-grades

The real costs on GCP (from `cost-estimate.md` and `docs/evidence/README.md`):
- **Flink:** $0.90 per run (resource count 71, total 59 min 31 s)
- **Flink-jdbc:** $0.94 per run (resource count 81, total 56 min 45 s)
- **Beam Dataflow:** $2.33 per run (resource count 75, total 2 h 31 min)

**The ADL formulas:**
```
DEFINE CONST COST_FLINK AS 0.90
DEFINE CONST COST_FLINK_JDBC AS 0.94
DEFINE CONST COST_BEAM AS 2.33
DEFINE CONST COST_RATIO_BEAM_TO_FLINK AS COST_BEAM / COST_FLINK  ≈ 2.59
ASSERT(Total GCP cost per run for flink <= COST_FLINK + ε)
ASSERT(Total GCP cost per run for flink-jdbc <= COST_FLINK_JDBC + ε)
ASSERT(Total GCP cost per run for beam <= COST_BEAM + ε)
ASSERT(COST_RATIO_BEAM_TO_FLINK > 2.0  →  Beam costs more than twice what Flink does)
```

**MCP monitoring:** `get_cost_last_run(run_id)` — runs after every run, compares the real cost with the ADL thresholds and alerts on any breach.

## Task 3: A defensive recommendation + the recovery ADL

**Recommendation:** a combination of **Flink (for performance and cost)** + **one streaming variant (for eventual scalability)**.

**The recovery ADL (a reconciliation fitness function) that monitors the risk:**
```
DEFINE CONST MAX_RUNTIME_FLINK AS 60min
DEFINE CONST MAX_RUNTIME_BEAM AS 180min
DEFINE CONST MAX_COST_FLINK AS 1.00  # a ~10% margin above $0.90
DEFINE CONST MAX_COST_BEAM AS 2.60    # a ~10% margin above $2.33

ASSERT(actual_runtime_flink_run_<run_id> <= MAX_RUNTIME_FLINK)
ASSERT(actual_cost_flink_run_<run_id> <= MAX_COST_FLINK)
ASSERT(no submitted answer is lost while Grading is unavailable)
ASSERT(Answer Database never exceeds MAX_DB_CONNECTIONS connections)
```

This fitness function runs periodically (monthly, or after every large run) and alerts the team when the costs or runtimes pass acceptable limits — so intervention happens before the costs spiral.

---
*Generated in the K09 quadrant — The business environment and cost*