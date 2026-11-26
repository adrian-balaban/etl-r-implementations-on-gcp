# K14. Cuantificarea capabilităților

## Scenariu (5 min)
Echipa dorește să măsoare capacitățile structurale ale arhitecturii: simplitate, deployabilitate, scalabilitate, cost. Care e statusul fiecărei implementări?

## Material sursă
- `docs/evidence/README.md` (numere reale: cost, timp, resurse)
- `cost-estimate.md` (cost-uri reale pe GCP: $0.90 / $0.94 / $2.33)
- `make spec-test` / `make conformance-all` (testează toate implementările vs. oracol)
- `make-the-grade-pages-811-827/` (FFDA template – load-bearing verdicts)
- Diagrame: `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Capability ADL:** Simplicity (# resurse Terraform: 71/81/75), Deployability (ramp-up), Scalability (200k la paralelism 1 → start-up domină), Cost.
2. **Ce spun datele despre fit-ul fiecărei implementări pentru fiecare capabilitate?** Ce e trend și ce e un singur-run (Limits!).
3. **Ce metrice temporale lipsesc în evidence pack-uri** ca să poți spune "readiness" (checklist §8: o singură măsurătoare nu e dovadă).
4. **ADL composite:** Maintainability {main-sequence D < 0.3, complexitate < 4, resurse < 100}.

## Livrabil ADL (punct de start)

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