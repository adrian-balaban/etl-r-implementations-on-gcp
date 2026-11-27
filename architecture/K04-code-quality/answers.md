# K04 answers: Code quality and fitness functions

## Task 1: The cyclomatic complexity of `grade()`

**The real code (from `pipelines/common/tds.py` or its equivalent):**

```java
public Grade grade(Answer a) {
    if (a.response() == null)
        return Grade.WRONG;
    String key;
    switch (a.gradeLevel()) {
        case 3: key = grade3Key.get(a.questionNum()); break;
        case 4: key = grade4Key.get(a.questionNum()); break;
        // cases 5 through 11: identical, one per grade
        case 12: key = grade12Key.get(a.questionNum()); break;
        default: throw new IllegalArgumentException("grade");
    }
    if (a.type() == QuestionType.TRUE_FALSE)
        return a.response().equalsIgnoreCase(key) ? Grade.RIGHT : Grade.WRONG;
    return a.response().equals(key) ? Grade.RIGHT : Grade.WRONG;
}
```

**The analysis:**
- `null check` → 1 decision
- `switch cases, grades 3–12` → 10 decisions (cases 5–11 are identical; only the key varies)
- `TRUE_FALSE check` → 1 decision (with two ternaries)
- **Total: 14 decisions + 1 null check = 15**

**Essential or accidental?** The complexity is accidental: every case differs only through a table lookup (grade3Key, grade4Key, ...), not through real behaviour. Each case reads one map entry; none runs complex logic.

## Task 2: A / I / D (Abstractness/Instability/Distance)

**Metrics for the ETL-R repo (following the make-the-grade model):**

| Component | A (Abstractness) | I (Instability) | D (Distance) | Zone |
|---|---|---|---|---|
| AutoGrading | 0.00 (all implementation) | 0.14 | 0.86 | **Zone of Pain** — a heavily depended-upon implementation; every change propagates to 6 callers |
| QuestionSender | 0.25 | 0.60 | 0.15 | **Pass** — few dependencies, stable |
| AnswerCapture | 0.20 | 0.80 | 0.00 | **Pass** — very unstable, but with low abstractness |
| TestLoader | 0.38 | 0.40 | 0.22 | **Pass** — balanced |
| ReportingCore | 0.75 | 0.86 | 0.61 | **Fail** — Zone of Uselessness — mostly abstractions, little behaviour, large fan-out |

**The formulas:**
- A = ΣNa / (ΣNa + ΣNc) — the ratio of abstract classes to the total
- I = Ce / (Ce + Ca) — the ratio of efferent dependencies to the total
- D = |A + I − 1| — the distance from the main sequence (ideal: D < 0.3)

## Task 3: An ADL with thresholds

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

## Task 4: A refactoring onto the main sequence

**The proposed refactoring:**
1. Extract Grader, AnswerKeyProvider and AnswerMatcher into separate interfaces
2. TrueFalseMatcher and MultipleChoiceMatcher stay concrete
3. Key loading moves into the Answer Key Loader
4. After the refactoring: AutoGrading becomes 2 abstract + 3 concrete classes: A = 0.60. I stays 0.14. D = 0.26: **Pass**.

**The reason:** it removes the "Zone of Pain" the right way — abstractions at component level, not at method level.