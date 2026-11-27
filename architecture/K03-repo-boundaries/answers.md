# K03 answers: Boundaries between implementations in the monorepo

## Task 1: Explicit vs. implicit ADL

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

**Compare the variants:**

| Variant | Syntax | What it catches | What it misses |
|---|---|---|---|
| **Explicit** | `ASSERT(flink HAS NO DEPENDENCY ON flink-jdbc, beam-dataflow)` | any new cross-implementation dependency the agent adds | an unanticipated dependency on a new service nobody listed |
| **Implicit** | `ASSERT({flink, flink-jdbc, beam-dataflow} HAVE NO DEPENDENCIES ON OTHER SERVICES)` | any new dependency, including the unforeseen ones | too strict: it blocks intentional sharing (e.g. the shared `artefacts.json`) |

**Which one catches the agent?** The `verify-project2` test (running `git diff --quiet -- pipelines apps`) catches implicitly, because any change in `pipelines/` or `apps/` fails the build. The explicit ADL catches only the dependencies it lists.

## Task 2: The cycle rule's pseudocode

```
FOREACH COMPONENT c IN system DO
    ASSERT(no cycles exist between c and other components)
END
```

**What happens:** the agent adds auto-imports for classes across packages, creating the cycle `A → B → C → A`. The rule above walks every component and checks that no cycle leads back into the others. If a cycle exists, the assertion fails and the fitness function reports a "structural violation".

**A runnable fitness function (Python/pytest sketch):**
```python
def test_no_component_cycles(import_helper):
    components = import_helper.get_all_components()
    for c in components:
        visited = set()
        if _has_cycle(import_helper, c, visited):
            pytest.fail(f"Cycle detected involving component {c}")
```

## Task 3: The shared-vs-duplicated table (real)

| Code | Decision | Reason |
|---|---|---|
| **Session-token validation** | Share | 0 changes in 90 days, 3 consumers: one implementation is critical for security |
| **Answer-key parser** | Share | stable copies; diverging copies could grade the same answer differently |
| **Answer message schema** | Share as a contract, not a library | the Testing → Grading contract: versioned, checked with consumer-driven contracts |
| **Roster CSV import** | Duplicate | 9 changes in 90 days at 2 consumers: duplication is cheaper than the churn of sharing |
| **Report PDF formatting** | Duplicate | the most volatile cell (23 changes): sharing would push the churn onto admin |

**Conclusion:** volatility decides — "duplication is preferable to coupling" is a spectrum, not a rule.

## Task 4: `verify-project2` formalized as ADL assertions

```
ASSERT(git diff --quiet -- pipelines apps)
ASSERT(contracts/ unchanged modulo artefact names)
ASSERT(a new project adds exactly one .sqlx in ../dataform/definitions/)
ASSERT(no hand-edit of spec/fixtures/ACCOUNT.src)
```
These run as a fitness function on every build, proving the new project does not touch the existing implementations' code.

---
*Generated in the K03 quadrant — Boundaries between implementations in the monorepo*