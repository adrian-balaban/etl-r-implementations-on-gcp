# K11 answers: Dynamic coupling and entanglement

## Task 1: A capability ADL for `produce` vs. `load`

**The current ETL-R capability definitions:**

```
DEFINE CONST max_user_load AS 200000
DEFINE CONST avg_response_time AS 300ms  -- for the Flink produce
DEFINE CONST avg_response_time AS 2000ms  -- for Auto Grading (slower)
```

**The conflicts with the synchronous call:**
- `produce` targets `max_user_load = 200,000` users at `avg_response_time = 300ms`
- `load` targets `max_user_load = 5,000` users (the Grading Service consumers) at `avg_response_time = 2000ms`
- if `produce` calls `load` synchronously, performance sinks to the slowest link: **5,000 users @ 2s**, not 200,000 users @ 300ms
- **dynamic quantum entanglement:** when independent services call each other synchronously, the operational capacity sinks to the weakest link in the chain

**The fitness-function ADL:**
```
DEFINE COMMUNICATION produce TO load AS asynchronous
     VIA Kafka topic target-system-target
ASSERT(actual response time at max_user_load <= avg_response_time)
     AT max_user_load for produce service
```

## Task 2: `DEFINE COMMUNICATION` for every relation

| Relation | The ADL definition | The assertion |
|---|---|---|
| **orchestrator → gate** | `DEFINE COMMUNICATION Test Session TO Gate AS synchronous` | `ASSERT(Communication between services adheres to defined declarations)` |
| **produce → load** | `DEFINE COMMUNICATION produce TO load AS asynchronous` `VIA Kafka topic target-system-target` | `ASSERT(no quantum publishes a shared-database channel)` |
| **mock → recon** | `DEFINE COMMUNICATION Answer Capture TO Recon AS asynchronous` | `ASSERT(settle targetRows confirmed matches written + rejected)` |
| **enrich → target** | `DEFINE COMMUNICATION enrichment TO target AS asynchronous` `USING reference/branches.json` | `ASSERT(READONLYREPLICA is cached copy of reference data)` |

**Important:** every communication is **asynchronous**, so the answers survive Grading outages and bursts; grading may trail a few seconds per student. Order matters only in Testing, where each answer gets the next question from the local cache.

## Task 3: `enrich` with reference data

**The READONLYREPLICA alternative:**
- `enrich` joins against `reference/branches.json` (a static lookup table).
- You can model it as `READONLYREPLICA branches AS branch-cache WITHIN Grading Quantum`.
- **What stays statically coupled:** only temporal coupling — the cache sits next to the quantum, static, and reloads before every next test window.
- **What you lose:** no synchronous access to the reference data during the test; you must prepare the cache beforehand.

## Task 4: What the run loses without Kafka's protection

**Fault tolerance at the dead mock:**
- The real variance: the Target System mock's Kafka login gives out after ~34 min.
- The consequence: the `load` step fails (`unsettled=N`, `RECONCILIATION FAILED`).
- **With Kafka:** in the other runs (flink-jdbc, beam-dataflow) the mock died too, and **no data was ever lost silently** — the runs recovered by recycling the Cloud Run revision, and the retries went through.
- **Without Kafka:** with a synchronous producer (the debugging agent's design), when the mock dies **every in-flight record is lost** — no durable queue as protection, no `src_read = written + rejected` reconciliation, and the producer crashes on the spot.

**ETL-R's pattern:** Kafka is a durable buffer; if the producer dies, the messages wait in the queue until the consumer returns. Synchronous calls lose that durability.