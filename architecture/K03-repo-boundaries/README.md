# K03. Boundaries between implementations in the monorepo + the forbidden cycle

## Scenario (5 min)
An agent asked to "reuse the parser from flink in beam-dataflow" adds dependencies between implementations; another creates a component cycle through auto-imports. Per `CLAUDE.md`, shared things are edited only in `spec/`.

## Source material
- `CLAUDE.md` ("Edit shared things in `spec/` only… symlinks", "Nothing billable runs on GCP without asking first")
- `spec/contracts/README.md` (`verify-project2`, `git diff --quiet -- pipelines apps`)
- `spec/PIPELINE.md` (eight fixed stages, the `src_read = written + rejected` balance)
- `harness/generate.py` (generates the inputs and the signal tests)
- `make spec-test` / `make conformance-all` (the conformance tests)
- Diagrams: `docs/diagrams/00-overview.svg`, `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Explicit ADL (forbidden pairs) vs. implicit ADL (`HAS NO DEPENDENCY ON other services`)** — which one catches the agent?
2. **The cycle rule's pseudocode:** `FOREACH COMPONENT c IN system DO ASSERT(no cycles exist between c and other components) END` → a skill/fitness function of your choice.
3. **The shared-vs-duplicated table on real code:** `compare.py`, `harness/generate.py`, the mapping — where is the volatility?
4. **`verify-project2` formalized as a runnable ADL assertion.**

## ADL deliverable (starting point)

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