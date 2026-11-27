# K17 answers: Agents and delegated judgment

## Task 1: The generation scope = one quantum, not "refactor everything"

**The correct definition:**
- The agent's scope is limited to one implementation (one quantum) — not to refactoring every implementation, and not the spec.
- The ADL includes `DEFINE AGENT Spark Builder AS spark-agent` together with restrictive assertions.

**The assertions that bound the scope:**
```
DEFINE AGENT Spark Builder AS spark-agent
     DEFINE COMPONENT SparkSubmit AS spark-submit
     DEFINE SERVICE SparkCluster AS spark-cluster
     ASSERT(agents cannot add cross-implementation dependencies)
     ASSERT(agents cannot add undeclared database connections)
     ASSERT(agent-generated changes stay within declared structural and operational boundaries)
```

**The reason:** with a global scope, the agent can accidentally create dependencies that break `ASSERT(no cycles exist between services in ETL-R)` or `ASSERT(Do not allow static coupling between quanta not specified here)`.

## Task 2: Deterministic checking vs. the LLM judge

**Why determinism comes first:**
- **Conformance 68/68** against the oracle is objective, deterministic verification, run by `make spec-test` / `make conformance-all`.
- **The LLM judge** is subjective, non-deterministic, and must be used only as an interim guardrail — never as a build gate.

**Where human judgment remains:**
- **Random probes:** pick rows from the agent's output for visual quality checks.
- **Human review:** a specified percentage of essays (e.g. 2% of the final essay grades) checked by real people.
- **The recommended rate:** the course checklist says: "2% spot checks of final essay grades" + "human reviews SPOT_CHECK_RATE of final essay grades".

**ETL-R's pattern:**
- The build blocks if `make conformance` fails (deterministic).
- The LLM judge runs only on demand, or to generate documents — never as a CI/CD gate.

## Task 3: `reject_log` = a complete DLQ

**The essential part that cannot be fully automated:**
1. **Enumerated reasons:** `PARSE_BAD_COLUMN_COUNT`, `PARSE_BAD_NUMERIC`, `PARSE_BAD_DATE`, `MAP_UNMAPPED_ENUM_VALUE`, `SCHEMA_INVALID` – never free text.
2. **The human path:** `record_lineage` traces the origin of every record placed in the DLQ – needed when the agent cannot determine the root cause.
3. **The fitness function:** `ASSERT(when the answer queue is drained, every answer receipt in Test Database has a graded answer in Grading Database)` – deterministic, not an LLM.
4. **The human last mile:** if the DLQ is not empty after N minutes of testing, an operator reviews the errors manually and decides whether to fix the mapping or accept the defect.

**The complete recipe:**
- `reject_log` with columns: `run_id`, `account_key`, `reason` (enumerated), `detail` (literal, as in the contract).
- `record_lineage` with: `run_id`, `account_key`, `source_key` (raw:<hash>).
- Runnable fitness functions: `get_queue_depth()` + `get_graded_key_hash()`, run at the end of the test window.
- The human path: if the DLQ is not empty after 5 minutes, an operator reviews manually and acts.

## Task 4: An MCP server over the ETL-R governance context

**The metrics running on the MCP server:**
- `get_balance(run_id)` – runs when a test window closes, checks `src_read = written + rejected`.
- `get_conformance()` – runs `make conformance-all` and returns the pass percentage against the oracle.
- `get_cost_last_run(run_id)` – measures the real GCP cost and compares it with the ADL thresholds.

**Who runs them, and how often:**
- **CI/CD:** `get_conformance()` runs on every build/PR (deterministic, the build gate).
- **Monthly:** `get_cost_last_run()` runs monthly, alerting when costs exceed the ADL thresholds.
- **After every test window:** `get_balance(run_id)` runs immediately, alerting the team if the run does not settle.

**Recommendation:** all three run automatically, but only `get_conformance()` gates the build. The other two are alerts; they do not block the build.