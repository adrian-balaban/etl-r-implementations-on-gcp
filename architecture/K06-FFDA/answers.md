# K06 answers: FFDA — Fitness Function Driven Architecture

## Task 1: Keep the load-bearing functions

| Candidate | Verdict | Reason |
|---|---|---|
| **1. Answer Capture accepts 200,000 concurrent submissions within 300 ms** | **Load-bearing** | protects the scalability requirement the business defined (200k students) |
| **2. No submitted answer is lost, even while Grading is down** | **Load-bearing** | protects the "no data loss" requirement — a direct consequence of the educational requirements |
| **3. The answer database never exceeds 300 connections** | **Load-bearing** | infrastructure-reserved capacity (cost + performance) |
| **4. Answer keys are unreachable from student-facing services** | **Load-bearing** | security + domain separation (Conway's Law) |
| **5. Every class has a unit test** | **Reject** | a unit-testing concern — not architecture; agents do not need it |
| **6. Every method has cyclomatic complexity under 5** | **Reject** | too brittle at system level; apply it as a metric gate on agent-generated packages |
| **7. The admin UI uses the approved color palette** | **Reject** | a linter or engineering check: not architecture, style |
| **8. Report PDFs generate in under 10 seconds** | **Not now** | real, but secondary; add it later if users report slowness |
| **9. Every service logs in JSON** | **Reject** | a linter/style check: noise, not architecture |
| **10. The test scheduling screen loads in under 1 second** | **Not now** | real, but secondary; UX, not load-bearing |

**The golden rule:** keep only what holds up the structure (load-bearing), not the wallpaper. Full coverage with false-alarm functions teaches people to ignore failures.

## Task 2: Write them in ADL before the code

The ETL-R repo already has the balance in its `README.md` (real numbers measured on GCP). The load-bearing functions, written in ADL:

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

These are the first four functions, written **before the code**, in the LMAX style (the first one to pass).

## Task 3: Which one gets written first (the LMAX style)

**The answer:** **function #1** — "the 200,000-submission load test". Write it first and let it fail for weeks, until the team finds a design that passes; everything else is secondary.

That is why, in the ETL-R repo, **the balance (`src_read = written + rejected`)** was the first function written (`make spec-test` validates it row by row).

## Task 4: "Cost/run" fails in week 4 → not a bug

**The disposition (the course rule):**
- **Fix:** if it follows a legitimate design change (say, optimizing the pipeline to cut start-up time)
- **Revise:** if the topology needs revisiting (say, changing the number of Dataflow workers)
- **Exemption** with owner and deadline: "cost/run fails although feature X is still unfinished" → an explicit, never-ignored exemption, owner: "Platform Team", expiry: "next quarter review"

---
*Generated in the K06 quadrant — FFDA*