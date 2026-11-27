# K09. Mediul de business și cost

## Scenariu (5 min)
Banca vrea 20 de proiecte simultan și / sau 20 milioane de înregistrări pe run; o lege de residency apare într-o altă regiune. Cum reacționează fiecare implementare?

## Material sursă
- `docs/evidence/README.md` (numere reale: cost, timp, resurse)
- `cost-estimate.md` (cost-uri reale pe GCP: $0.90 / $0.94 / $2.33)
- `make spec-test` / `make conformance-all` (validare rând-cu-rând)
- `SECTIONS.md` (paginile 62–63 din masterclass)
- Diagrame: `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Cum reacționează fiecare implementare la fiecare scenariu**; unde e riscul?
2. **Cost ADL pe 2 $-grade:** trece `beam-dataflow` la 20M (Composer $1.03 e deja fixed)?
3. **Recomandare defensivă** (un Flink + o variantă de streaming justificată?) + ADL de revenire (reconciliation FF) care monitorizează riscul.

## Livrabil ADL (punct de start)

```
DESCRIPTION Define business environment and cost fitness functions
CATEGORY Engineering Practices
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE CONST MAX_STUDENTS AS 200000
     DEFINE CONST MAX_SUBMIT_TIME AS 300ms
     DEFINE CONST MAX_DB_CONNECTIONS AS 300
     DEFINE CONST COST_PER_RUN_BASE AS 0.90
     DEFINE CONST COST_BEAM_OVERHEAD AS 2.33 / 0.90
ASSERT(Answer Capture accepts MAX_STUDENTS concurrent submissions within MAX_SUBMIT_TIME)
ASSERT(Total GCP cost per run <= COST_PER_RUN_BASE * (1 + COST_BEAM_OVERHEAD) for beam)
ASSERT(no submitted answer is lost while Grading is unavailable)
ASSERT(Answer Database never exceeds MAX_DB_CONNECTIONS connections)
```