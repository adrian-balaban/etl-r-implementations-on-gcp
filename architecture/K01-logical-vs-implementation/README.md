# K01. Logical vs. implementation architecture

## Scenario (5 min)
An agent generated the directory structure of the `flink` implementation in the ETL-R repo. Everything runs: `make conformance` returns `68 / 68, staged`. The logical architecture matches the code's structure — but is that enough?

## Source material
- `implementations/flink/` (`gate/`, `recon/`, `sql/`, `variant-flink-sql-batch-job/`, `variant-flink-datastream-batch-job/`, `target-system-mock/`)
- `spec/PIPELINE.md` (eight fixed stages, the balance equation `src_read = written + rejected`)
- `CLAUDE.md` ("Never commit unless asked")
- Diagrams: `docs/diagrams/flink/c2-containers.svg`, `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Read the directories as architecture** — which domains, subdomains and components does the repo's structure describe?
   - Identify any orphan classes (the Entity Trap: `sql/` named after tables, not after behaviour).
2. **Write the domains ADL** for the whole repo (`spec/`, `implementations/<x>`, `harness`, `conformance`) plus the directory structure it assumes.
   - `ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)` — what it catches and what it misses.
3. **How would it simplify the life of an agent generating implementation #4** with this ADL as its context?
   - What code would it not need?

## ADL deliverable (starting point)

```
DESCRIPTION Define ETL-R domains
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DOMAIN Spec AS spec
     DEFINE SERVICE flink AS implementations/flink
     DEFINE SERVICE flink-jdbc AS implementations/flink-jdbc
     DEFINE SERVICE beam-dataflow AS implementations/beam-dataflow
ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)
```