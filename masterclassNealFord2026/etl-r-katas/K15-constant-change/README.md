# K15. Schimbare constantă: stressori și rezidii

## Scenariu (5 min)
Matricea reală de stressori: S1 proiect nou (DEPOSIT), S2 GCP schimbă prețul sau degradează Managed Kafka, S3 20M înregistrări, S4 Target System API v2 la confirmations, S5 altă regiune/residency, S6 se cere o 4 implementare (Spark/Dataproc).

## Material sursă
- `SECTIONS.md` (paginile 66–67 din masterclass)
- `docs/evidence/README.md` (What went wrong, Limits)
- `cost-estimate.md` (costuri reale)
- `make conformance-all` (testează toate implementările)
- Diagrame: `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Completează matricea:** ce componente loves S1–S6 (spec, contracte, flink, flink-jdbc, beam, harness, recon).
2. **Proiectează rezidii:** contracts-as-config (există!), oracol pinned, stage-mapping abstract.
3. **Prioritizează pe 3 teste:** zonă de business, # stressori acoperiți, probabilitate × impact.
4. **Scrie una în ADL:** `ASSERT(adding a project modifies only spec/contracts/) ` – armazarea lui asigură că nici agenții nu o îndepărtează ușor.