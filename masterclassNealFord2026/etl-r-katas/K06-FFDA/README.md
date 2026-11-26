# K06. Architecture condusă de fitness functions (FFDA)

## Scenariu (5 min)
Înainte de orice cod, echipa a propus 10 candidați pentru ETL-R. Care se țin și care se respinge?

## Material sursă
- `docs/evidence/README.md` (numere reale: cost, timp, resurse)
- `make spec-test` / `make conformance-all` (validare rând-cu-rând)
- `cost-estimate.md` (cost-uri reale pe GCP)
- `make-the-grade-pages-1705-1723/FFDA template` (load-bearing verdicts)
- Diagrame: `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Alege cele care susțin greutatea structurii**; respinge explicit restul cu motiv și unde aparțin (linter, check ingineresc).
2. **Scrie-le în ADL năprins de cod** (repo-ul are deja balanța în README).
3. **Care se scrie primul, în stilul LMAX?** (raspunsul: 1 — și repo-ul l-a scris pe primul loc).
4. "Cost/run" pică în săptămâna 4 → nu-i bug; dispoziție: fix / revise / exemption cu owner și termen.

## Livrabil ADL (pore de start)

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