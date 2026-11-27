# K12. The run as a saga

## Scenario (5 min)
One run = 8 stages across 3–4 deployment units; nothing may be lost; agents will regenerate these pipelines next year.

## Source material
- `spec/PIPELINE.md` (eight fixed stages, the `src_read = written + rejected` balance)
- `make conformance-all` (tests every implementation against the oracle)
- `make-the-grade-pages-1432-1458/` (the close-test workflow tasks)
- Diagrams: `docs/diagrams/03-two-doors.svg`, `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **The three forces:** Communication = asynchronous (topics), Coordination = orchestrated (the Cloud Run job / the Composer DAG — who is the orchestrator in `flink` vs. `beam`?), Consistency = eventual, verified (the recon balance + settle) – the arguments, per requirement.
2. **"Atomic + choreography" — what it becomes in reality, and why it is ungovernable.**
3. **The ADL:** `DEFINE SAGA Migration Run Saga` + a strict contract for the target document (JSON Schema — consumer-driven confirmations).
4. **The regeneration scope** = the quantum / the job, with a guardrails table: behaviour (conformance 68/68), static coupling (no new coupling), dynamic (still asynchronous), contract (the schema intact), capability (run time).