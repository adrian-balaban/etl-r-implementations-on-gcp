# K04. Calitate cod și fitness functions

## Scenariu (5 min)
Echipa vrea să mențină calitatea codului AutoGrading, însă nu știe să calculeze complexitatea ciclomatică, abstractness/instability sau distanța față de main sequence. CI raportează metricile, dar sunt o fitness function?

## Material sursă
- `pipelines/common/tds.py` (RecordParser, complexitate reală)
- `spec/conformance/compare.py` (comparatorul rând-cu-rând)
- `make spec-test` / `make conformance-all` (testele de calitate)
- `make-the-grade-pages-52-53/static-coupling.adl` (template ADL)
- `cost-estimate.md` (cost-uri reale pe GCP)
- Diagrame: `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Calculează complexitatea ciclomatică** pentru metoda `grade()` reală — esențială sau accidentală?
2. **Calculează A / I / D** (`D = |A + I − 1|`, prag 0.3) per componentă reală; desenează main-sequence.
3. **ADL cu praguri**, cu `MAX_CC_GENERATED` mai strict pentru cod de agent.
4. **Refactorizare** care mută componenta pe secvența principală.

## Livrabil ADL (punct de start)

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