# K14 answers: Quantifying capabilities

## Task 1: The capability ADL

**Simplicity** (# Terraform resources, complexity, cost):
- **Flink:** 71 resources (best), migration CC 4.6 (orange zone if >5), cost $0.90 (best)
- **Flink-jdbc:** 81 resources, CC 4.6, cost $0.94
- **Beam Dataflow:** 75 resources, cost $2.33 (worst, ~2.5× Flink)

**Deployability** (ramp-up + run time):
- **Flink:** ramp-up 23 min 36 s (worst, because of Kafka), run 6 min 55 s (best, at parallelism 1)
- **Flink-jdbc:** ramp-up 14 min 25 s (best), run 5 min 59 s (best)
- **Beam Dataflow:** ramp-up 50 min 56 s (worst, Composer + Dataform), run 38 min 25 s

**Scalability** (200k users, start-up dominant):
- **Flink:** 3 jobs, parallelism 1, on one 4-vCPU GKE node. At 200k, start-up dominates.
- **Flink-jdbc:** similar GKE, but loading the 200k rows into the source Postgres happens before the run (4 min 20 s).
- **Beam Dataflow:** 3 Dataflow jobs, one 1-vCPU worker each, plus Dataform. Run 38 min 25 s, but that includes a retry (without it ~28 min).

**Cost:**
- **Flink:** $0.90 (resource count 71)
- **Flink-jdbc:** $0.94 (resource count 81, +$0.04)
- **Beam Dataflow:** $2.33 (resource count 75, but the Composer $1.03 is fixed)

**The capability conclusions:**
- **Simplicity:** Flink wins (fewest resources, lowest cost)
- **Deployability:** Flink-jdbc wins (shortest ramp-up, fastest run)
- **Scalability:** Flink and Flink-jdbc are equivalent at start-up; Beam is slower because of the Composer overhead
- **Cost:** Flink is the absolute winner ($0.90 vs. $0.94 vs. $2.33)

## Task 2: What the data says about each implementation's fit

**The trends (from the evidence packs, exactly one run each):**
- **Flink:** low cost, average run time, optimal resources, but Kafka can add ~10 min of ramp-up variance (cloud variance).
- **Flink-jdbc:** cost very close to Flink, the fastest run time, resources slightly above average, but it needs 2 Cloud SQL instances (source + pipeline).
- **Beam Dataflow:** the highest cost (~2.5× Flink), the slowest run time and ramp-up, but the best isolation (fault tolerance ★★★★★) and no equally severe Kafka variance.

**What is a trend vs. what is a single run (the Limits!):**
- The data is one run per implementation, not a statistical series.
- The course's checklist §8 underlines: "a single passing measurement is not evidence of readiness".
- To call an architecture "ready" you need monthly trends, not one run.

**The temporal metrics missing from the evidence packs:**
- No time-to-first-byte metrics per stage (only totals).
- No CPU utilization metrics during the run.
- No memory pressure or garbage collection pause metrics.
- Missing: connection waits per database per run (only Flink-jdbc has source + pipeline PostgreSQL).

## Task 3: The composite ADL: Maintainability

```
DEFINE CONST MAX_CC AS 10
DEFINE CONST MAX_DISTANCE AS 0.3
DEFINE CAPABILITY Maintainability AS {
    average cyclomatic complexity of cls < MAX_CC,
    normalized distance from the main sequence for comp < MAX_DISTANCE,
    resources < 100
}
FOREACH component : comp IN system
    FOREACH class : cls IN comp
        ASSERT(cyclomatic complexity of m < MAX_CC FOR method m)
    END
    ASSERT(normalized distance from the main sequence for comp < MAX_DISTANCE)
ENDFOREACH
ASSERT(resources per component < 100)
```

**The interpretation:**
- **Zone of Pain (D > 0.3):** with D above 0.3 the component sits in the "pain" zone — every change is expensive and the regression risk is high.
- **Zone of Uselessness (A > 0.6, I > 0.75):** predominantly abstract, it is useless — no behaviour, just architecture.
- **The ideal:** D < 0.3, A between 0.2–0.6, I < 0.5 — the component sits on the main sequence, easy to maintain and extend.

## Task 4: Applied to ETL-R

Scoring each implementation on the capabilities:
1. **Flink:** can pass the maintainability tests if `grade()` is refactored into interfaces (as in K04's refactoring tasks). The cost is minimal, but the Kafka ramp-up is the main risk.
2. **Flink-jdbc:** can pass with minimal effort — its CC is similar to Flink's, and it has no Kafka variance. The cost is minimal (+$0.04). Recommended for new projects with a Postgres source database.
3. **Beam Dataflow:** has serious maintainability challenges because of the Composer and Dataform overhead. The high cost ($2.33) makes it unsuitable for 6-month deadlines, although it is the most robust through its fault tolerance.

**Recommendation:** for new projects with a 6-month deadline and 200k students, **Flink-jdbc** if the source is a Postgres database, or **Flink** for TDS files. Beam is justified only for small workloads, or when cost is not the deciding factor.

---
*Generated in the K14 quadrant — Quantifying capabilities*