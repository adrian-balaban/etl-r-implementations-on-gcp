# K03. Frontiere între implementări în monorepo + ciclu interzis

## Scenariu (5 min)
Un agent rugat să "refolosească parserul din flink în beam-dataflow" adaugă dependențe între implementări; altul creează un ciclu de componente cu auto-import. Conform `CLAUDE.md`, editează lucrurile partajate doar în `spec/`.

## Material sursă
- `CLAUDE.md` ("Editează doar în `spec/` lucrurile partajate… symlink-uri", "Nothing billable runs on GCP without asking first")
- `spec/contracts/README.md` (`verify-project2`, `git diff --quiet -- pipelines apps`)
- `spec/PIPELINE.md` (8 stațiuni fixe, echilibrul `src_read = written + rejected`)
- `harness/generate.py` (generează inputurile și testele semnale)
- `make spec-test` / `make conformance-all` (testele de conformitate)
- Diagrame: `docs/diagrams/00-overview.svg`, `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **ADL explicit (perechi interzise) vs. ADL implicit (`HAS NO DEPENDENCY ON other services`)** — care prinde agentul?
2. **Pseudocodul regulii de ciclu:** `FOREACH COMPONENT c IN system DO ASSERT(no cycles exist between c and other components) END` → skill/fitness function la alegere.
3. **Tabelul de partajat vs. duplicat pe cod real:** `compare.py`, `harness/generate.py`, mapping-ul — unde e volatilitatea?
4. **`verify-project2` formalizat ca ADL aserțiune rulabilă.**

## Livrabil ADL (punct de start)

```
DESCRIPTION Define monorepo service boundaries
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE SERVICE flink AS implementations/flink
     DEFINE SERVICE flink-jdbc AS implementations/flink-jdbc
     DEFINE SERVICE beam-dataflow AS implementations/beam-dataflow
     DEFINE LIBRARY Contracts AS spec/contracts
ASSERT(flink HAS NO DEPENDENCY ON flink-jdbc, beam-dataflow)
ASSERT(flink-jdbc HAS NO DEPENDENCY ON flink, beam-dataflow)
ASSERT(beam-dataflow HAS NO DEPENDENCY ON flink, flink-jdbc)
ASSERT(no cycles exist between services in ETL-R)
```