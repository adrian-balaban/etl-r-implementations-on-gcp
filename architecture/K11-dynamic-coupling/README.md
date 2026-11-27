# K11. Dynamic coupling and entanglement

## Scenario (5 min)
The agent "simplifies debugging": `produce` now calls `load` synchronously (REST) instead of going through Kafka. Also: "streaming mode is a defect" — why is bounded batch an architecture principle (snapshot, balance, recon)?

## Source material
- `spec/PIPELINE.md` (eight fixed stages, `streaming mode is a defect`)
- `docs/evidence/README.md` ("What went wrong", the Kafka variance, the dead mock)
- `make conformance-all` (tests every implementation against the oracle)
- `make-the-grade-pages-1379-1398/` (dynamic coupling tasks)
- Diagrams: `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **A capability ADL for `produce` vs. `load`** (users, target time) – which targets conflict with the synchronous call; explain dynamic quantum entanglement.
2. `DEFINE COMMUNICATION` for every relation (orchestrator→gate: synchronous; produce→load: asynchronous Kafka; mock→recon: asynchronous confirmations) + the assertion that keeps agents in line.
3. `enrich` with reference data (`reference/branches.json`): the READONLYREPLICA alternative and the remaining static coupling.
4. What the run loses without what Kafka protects: fault tolerance at the dead mock.