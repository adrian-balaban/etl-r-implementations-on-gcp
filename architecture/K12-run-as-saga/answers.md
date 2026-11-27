# K12 answers: The run as a saga

## Task 1: The three forces

| Force | The ADL definition | The arguments on ETL-R's requirements |
|---|---|---|
| **Communication** | `DEFINE COMMUNICATION produce TO load AS asynchronous` `VIA Kafka topic target-system-target` | the Kafka topics are durable; no reply is needed immediately; answers flow durably; any service consumes at its own pace. |
| **Coordination** | `DEFINE COORDINATION Orchestrated` `DEFINE SAGA Migration Run Saga` | the Cloud Run job (or the Composer DAG) is the orchestrator; it knows when the queue empties and owns error handling. |
| **Consistency** | `DEFINE CONSISTENCY Eventual` `ASSERT(settle targetRows confirmed matches written + rejected)` | "no answer lost" is durability, not atomicity; grades and reports may trail by minutes, but all are confirmed in the end. |

**The synthesis, per requirement:**
- **Communication** makes sure the messages are never lost — the Kafka topics buffer them.
- **Coordination** (orchestrated) makes explicit who owns stopping and resolving errors.
- **Consistency** (eventual) confirms that "no lost answers" is a durability property, not atomicity.

## Task 2: What "atomic + choreography" becomes

**What it really is:** no singleton stores the transaction state of the whole 8 stages. Every service stores its own part (Flink: the JobManager; Beam: the Dataflow job state). To see the global state you must **add a service** that tracks it — and that service is the orchestrator.

**Why it is ungovernable:** without an explicit orchestrator:
- Every service holds a slice of the state (e.g. `produce` counts what it writes, `load` counts what it confirms).
- No entity sees the total state (`src_read = written + rejected`).
- A teammate who wants "atomic consistency" effectively creates an **orchestrator under another name** — a topology change, not a solution to the problem.

**The result:** "atomic + choreography" becomes the Orchestrator pattern with a dedicated service (a Cloud Run job or a Composer DAG).

## Task 3: The ADL: `DEFINE SAGA Migration Run Saga`

```
DESCRIPTION Define Migration Run Saga for ETL-R
CATEGORY Integration
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE SAGA Migration Run Saga AS
          DEFINE COMMUNICATION Test Session TO Answer Capture AS asynchronous
          DEFINE COMMUNICATION Answer Capture TO Auto Grading AS asynchronous
          DEFINE COMMUNICATION Auto Grading TO Reporting AS asynchronous
          DEFINE COORDINATION Orchestrated
          DEFINE CONSISTENCY Eventual
     DEFINE CONTRACT graded-answer AS NAME-VALUE PAIRS
          INCLUDING {student-id, test-id, question-num, answer, receipt-id}
ASSERT(Communication between services matches the defined communication declarations)
ASSERT(Workflow is managed by the Test Session orchestrator)
```

**The `graded-answer` contract:** strict on names (NOT protocol), only the required fields ({student-id, test-id, question-num, answer, receipt-id}). Every implementation generates them with its own technology, picking the protocol (gRPC/REST/Kafka) – consumer-driven contract tests.

## Task 4: The regeneration scope = the quantum / the job

| Guardrail | The check |
|---|---|
| **Behaviour** | functional tests: grading results, edge cases, the answer contract (conformance 68/68) |
| **Static coupling** | no new coupling to other quanta; Grading DB access unchanged |
| **Dynamic coupling** | still consumes answers asynchronously; no new synchronous calls |
| **Contract** | graded-answer still accepts the required fields {student-id, test-id, question-num, answer, receipt-id} |
| **Saga** | still participates in the Migration Run Saga under the orchestrator |
| **Capability** | still meets 5,000 users at 2s (or 200,000 for Flink) |

**The quantum's role:** the regeneration scope is exactly one quantum. If an agent changes `spec/contracts/` (say, adds a field to `graded-answer`), it must pass the static-coupling and contract fitness functions before the build goes through.

---
*Generated in the K12 quadrant — The run as a saga*