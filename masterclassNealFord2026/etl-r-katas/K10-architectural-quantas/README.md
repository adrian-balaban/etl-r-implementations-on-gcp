# K10. Cuante arhitecturale pe implementare

## Scenariu (5 min)
Câte cuante are fiecare implementare? Terraform urlă tot (VPC, GKE, Cloud SQL, Managed Kafka, Cloud Run); infrastructura devine cuplaj static; shared Kafka cu mock-ul Target System.

## Material sursă
- `docs/diagrams/<impl>/deployment-gcp.svg` – per implementation
- `docs/diagrams/08-stage-mapping.svg` – stage mapping
- `spec/PIPELINE.md` (8 stațiuni, contractul Kafka)
- `docs/evidence/README.md` (resources: 71/81/75)
- `make conformance-all` (testează toate implementările)
- Diagrame: `docs/diagrams/flink/deployment-gcp.svg`, `docs/diagrams/beam-dataflow/deployment-gcp.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Enumeră pentru fiecare implementare punctele de cuplaj static** care leagă componentele; unde e frontiera față de "sistemul altei echipe" (Target System: topic-urile sunt contractul).
2. **Aplică Domain Broker:** un cluster Kafka per cuantă – what you win, what you lose (ramp-up, cost).
3. **ADL static-coupling:** `DEFINE QUANTUM` + `WITHIN` + aserțiuni de acces per implementare.
4. `ASSERT(Do not allow static coupling between quanta not specified here)` – oprește un agent de la generarea de cuplaj inter-cuantă.