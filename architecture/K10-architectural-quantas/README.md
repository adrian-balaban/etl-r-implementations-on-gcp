# K10. Architectural quanta per implementation

## Scenario (5 min)
How many quanta does each implementation have? Terraform provisions everything (VPC, GKE, Cloud SQL, Managed Kafka, Cloud Run); infrastructure becomes static coupling; Kafka is shared with the Target System mock.

## Source material
- `docs/diagrams/<impl>/deployment-gcp.svg` — per implementation
- `docs/diagrams/08-stage-mapping.svg` — the stage mapping
- `spec/PIPELINE.md` (eight stages, the Kafka contract)
- `docs/evidence/README.md` (resources: 71/81/75)
- `make conformance-all` (tests every implementation)
- Diagrams: `docs/diagrams/flink/deployment-gcp.svg`, `docs/diagrams/beam-dataflow/deployment-gcp.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Enumerate, per implementation, the static coupling points** that tie the components together; where is the boundary against "the other team's system" (the Target System: the topics are the contract)?
2. **Apply the Domain Broker:** one Kafka cluster per quantum — what you gain, what you lose (ramp-up, cost).
3. **The static-coupling ADL:** `DEFINE QUANTUM` + `WITHIN` + per-implementation access assertions.
4. `ASSERT(Do not allow static coupling between quanta not specified here)` – stops an agent from generating inter-quantum coupling.