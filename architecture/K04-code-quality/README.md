# K04. Code quality and fitness functions

## Scenario (5 min)
The team wants to keep the AutoGrading code quality high, but does not know how to compute cyclomatic complexity, abstractness/instability, or the distance from the main sequence. CI reports the metrics — but is that a fitness function?

## Source material
- `pipelines/common/tds.py` (RecordParser, real complexity)
- `spec/conformance/compare.py` (the row-by-row comparator)
- `make spec-test` / `make conformance-all` (the quality tests)
- `make-the-grade-pages-52-53/static-coupling.adl` (an ADL template)
- `cost-estimate.md` (real costs on GCP)
- Diagrams: `docs/diagrams/08-stage-mapping.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Compute the cyclomatic complexity** of the real `grade()` method — essential or accidental?
2. **Compute A / I / D** (`D = |A + I − 1|`, threshold 0.3) per real component; draw the main sequence.
3. **An ADL with thresholds**, with `MAX_CC_GENERATED` stricter for agent-generated code.
4. **A refactoring** that moves the component onto the main sequence.

## ADL deliverable (starting point)

```
DESCRIPTION Define code quality fitness functions
CATEGORY Engineering Practices
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE CONST MAX_CC = 10
     DEFINE CONST MAX_CC_GENERATED = 5
     DEFINE CONST MAX_DISTANCE = 0.3
     DEFINE CONST AVG_RESPONSE_TIME = 300ms
     DEFINE CONST MAX_STUDENTS = 200000
     DEFINE CONST MAX_SUBMIT_TIME = 300ms
FOREACH component : comp IN system
    FOREACH class : cls IN comp
        FOREACH method : m IN cls
            IF m IS AGENT-GENERATED
                ASSERT(cyclomatic complexity of m < MAX_CC_GENERATED)
            ELSE
                ASSERT(cyclomatic complexity of m < MAX_CC)
        END
    END
    ASSERT(average cyclomatic complexity of cls < MAX_CC)
    ASSERT(normalized distance from the main sequence for comp < MAX_DISTANCE)
ENDFOREACH
ASSERT(actual response time <= AVG_RESPONSE_TIME at MAX_STUDENTS)
```