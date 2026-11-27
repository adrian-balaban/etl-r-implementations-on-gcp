# K14. Quantifying capabilities

## Scenario (5 min)
The team wants to measure the architecture's structural capabilities: simplicity, deployability, scalability, cost. Where does each implementation stand?

## Source material
- `docs/evidence/README.md` (real numbers: cost, time, resources)
- `cost-estimate.md` (real costs on GCP: $0.90 / $0.94 / $2.33)
- `make spec-test` / `make conformance-all` (test every implementation against the oracle)
- `make-the-grade-pages-811-827/` (the FFDA template – load-bearing verdicts)
- Diagrams: `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **The capability ADL:** Simplicity (# Terraform resources: 71/81/75), Deployability (ramp-up), Scalability (200k at parallelism 1 → start-up dominates), Cost.
2. **What the data says about each implementation's fit for each capability.** What is a trend and what is a single run (the Limits!).
3. **Which temporal metrics are missing from the evidence packs** before you can say "ready" (checklist §8: a single measurement is not proof).
4. **The composite ADL:** Maintainability {main-sequence D < 0.3, complexity < 4, resources < 100}.

## ADL deliverable (starting point)

```
DESCRIPTION Define capability quantification for ETL-R
CATEGORY Engineering Practices
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE CONST MAX_RESOURCES AS 100
     DEFINE CONST MAX_CC AS 10
     DEFINE CONST MAX_DISTANCE AS 0.3
     DEFINE CAPABILITY Simplicity AS { resources < MAX_RESOURCES, cc < MAX_CC }
     DEFINE CAPABILITY Deployability AS { ramp-up < 30min, run < 60min }
     DEFINE CAPABILITY Scalability AS { max_students <= 200000, submit_time < 500ms }
     DEFINE CAPABILITY Cost AS { cost_per_run <= 1.0 USD }
FOREACH component : comp IN system
    ASSERT(capability Simplicity met FOR comp)
    ASSERT(capability Deployability met FOR comp)
    ASSERT(capability Scalability met FOR comp)
    ASSERT(capability Cost met FOR comp)
ENDFOREACH
```