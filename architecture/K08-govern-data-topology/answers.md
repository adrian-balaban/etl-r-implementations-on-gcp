# K08 answers: Governing the data topology

## Task 1: Which assertion breaks

The agent that "speeds recon up" by connecting its recon service straight to `etlr_extraction` (instead of running the specified recon) breaks:

```
ASSERT(Test Database is only accessed by Testing Domain Services)
ASSERT(Grading Database is only accessed by Grading Domain Services)
ASSERT(Admin Database is only accessed by Administration Domain Services)
ASSERT(Do not allow static coupling between quanta not specified here)
```

**Which test it fails:**
- **Static analysis (Terraform/IAM + manifests):** inspects the recon service's connectivity configuration. If recon-service holds a dependency on `etlr_extraction` in its Kubernetes manifest, the assertion breaks in the pull request or at build time, before any deploy.
- **Dynamic analysis (the Kafka flow):** if recon consumes straight from the input topic instead of awaiting the confirmations, the data flow will differ, and `settle` will no longer correlate with `written`+`rejected`.
- **Platform analysis (Cloud SQL logs):** the Cloud SQL logs will surface new, undeclared connections from recon-service to the `account_source` schema — the quantum boundary has been crossed.

**Which one catches it first?** Static analysis — the fastest and the most deterministic. If the Terraform Dependency Monitor sees an unauthorized link, the fitness function fails at pull-request level, before any runtime test.

## Task 2: Legitimate paths that respect the topology

**Path 1: replicate the data (data schema replication)**
- Testing owns read-only caches of the roster names in the Grading DB.
- Reports run after the test window, so a small lag is harmless.
- **Cost:** duplicated data, but then it survives Testing outages.

**Path 2: a data sidecar**
- Testing holds a simple, consistent cache of the roster names.
- The dependency on this sort of cache survives Testing outages.
- **Cost:** one more cache dependency, but isolated from the database dependencies.

**Path 3: a data-based payload contract**
- Answer Capture adds each student's name to the message (only on the busiest path).
- Bigger messages on the bus, but "no extra calls or stores".
- **Cost:** larger messages on the busiest path, but contained.

**Recommendation:** replicate the roster names into the Grading DB, refreshed when the roster changes. Reports run after testing, so a small lag is harmless.

## Task 3: The reconciliation fitness function

**What is examined, and when:**
| Analysis | What it inspects | When it captures a change |
|---|---|---|
| **Static** | Terraform values + Helm config + the Kubernetes manifest | in the pull request or at build, before deployment |
| **Dynamic** | service-mesh / Hubble flows: connections opened to the DB | only after the change runs (at runtime) |
| **Platform** | database-side connection logs, secrets-manager grants, DNS lookups | at runtime, from the database's side |

**The ETL-R-specific MCP tool:** `get_balance(run_id)` — runs when a test window closes, checks the `src_read = written + rejected` balance and alerts if it does not settle.

## Task 4: A crash between dequeue and persist

**The Asynchronous Chain of Persistence:**
1. **Synchronous send** – Answer Capture publishes the message on the Kafka topic `target-system-target` and waits for the client's acknowledgement (ack mode).
2. **Client acknowledge mode** – the client (the Test System) confirms the message only after persisting its confirmation.
3. **Last-participant support** – if Grading is slow or down, the queue protects the answers by persisting them; none are ever lost.

**The pattern ETL-R implements:**
- the `load` step publishes to the Kafka topic `target-system-target` with idempotency on the account key.
- the `recon` step checks `src_read = written + rejected` against the confirmations in `settle`.
- if the `load` step fails (the dead mock), `recon` marks the run `unsettled=N` + `RECONCILIATION FAILED`, but no data is ever lost silently — it sits in the DLQ with an enumerated reason.