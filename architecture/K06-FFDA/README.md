# K06. Architecture driven by fitness functions (FFDA)

## Scenario (5 min)
Before any code, the team proposed 10 fitness-function candidates for ETL-R. Which ones hold up the structure, and which get rejected?

## Source material
- `docs/evidence/README.md` (real numbers: cost, time, resources)
- `make spec-test` / `make conformance-all` (row-by-row validation)
- `cost-estimate.md` (real costs on GCP)
- the `make-the-grade-pages-1705-1723` FFDA template (load-bearing verdicts)
- Diagrams: `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Keep the ones that hold up the structure;** reject the rest explicitly, with the reason and where each belongs (a linter, an engineering check).
2. **Write them in ADL before the code** (the repo already has the balance in its README).
3. **Which one gets written first, LMAX-style?** (the answer: #1 — and the repo wrote it first).
4. "Cost/run" fails in week 4 → not a bug; the disposition: fix / revise / an exemption with owner and deadline.

## ADL deliverable (starting point)

```
DESCRIPTION Define load-bearing fitness functions for ETL-R
CATEGORY Engineering Practices
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE CONST MAX_STUDENTS AS 200000
     DEFINE CONST MAX_SUBMIT_TIME AS 300ms
     DEFINE CONST MAX_DB_CONNECTIONS AS 300
ASSERT(Answer Capture accepts MAX_STUDENTS concurrent submissions within MAX_SUBMIT_TIME)
ASSERT(no submitted answer is lost while Grading is unavailable)
ASSERT(Answer Database never exceeds MAX_DB_CONNECTIONS connections)
ASSERT(answer keys are unreachable from student-facing services)
```