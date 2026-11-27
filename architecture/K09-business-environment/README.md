# K09. The business environment and cost

## Scenario (5 min)
The bank wants 20 simultaneous projects and/or 20 million records per run; a residency law appears in another region. How does each implementation react?

## Source material
- `docs/evidence/README.md` (real numbers: cost, time, resources)
- `cost-estimate.md` (real costs on GCP: $0.90 / $0.94 / $2.33)
- `make spec-test` / `make conformance-all` (row-by-row validation)
- `SECTIONS.md` (pages 62–63 of the masterclass)
- Diagrams: `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **How each implementation reacts to each scenario;** where is the risk?
2. **A cost ADL on 2 $-grades:** does `beam-dataflow` scale to 20M (is the Composer $1.03 already a fixed cost)?
3. **A defensive recommendation** (one Flink + a justified streaming variant?) + a recovery ADL (a reconciliation FF) that monitors the risk.

## ADL deliverable (starting point)

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