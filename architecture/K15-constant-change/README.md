# K15. Constant change: stressors and residues

## Scenario (5 min)
The real stressor matrix: S1 a new project (DEPOSIT), S2 GCP changes prices or degrades Managed Kafka, S3 20M records, S4 a Target System API v2 for confirmations, S5 another region/residency, S6 a fourth implementation is requested (Spark/Dataproc).

## Source material
- `SECTIONS.md` (pages 66–67 of the masterclass)
- `docs/evidence/README.md` (What went wrong, Limits)
- `cost-estimate.md` (real costs)
- `make conformance-all` (tests every implementation)
- Diagrams: `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Fill in the matrix:** which components S1–S6 strike (spec, contracts, flink, flink-jdbc, beam, harness, recon).
2. **Design the residues:** contracts-as-config (it exists!), a pinned oracle, an abstract stage-mapping.
3. **Prioritize on three tests:** the business area, the # of stressors covered, probability × impact.
4. **Write one in ADL:** `ASSERT(adding a project modifies only spec/contracts/)` – keeping it ensures that not even agents remove it casually.