# Răspunsuri K04: Calitate cod și fitness functions

## Task 1: Complexitate ciclomatică pentru `grade()`

**Codul real (din `pipelines/common/tds.py` sau echivalentul său):**

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

**Analiza:**
- `null check` → 1 decision
- `switch cases, grades 3–12` → 10 decisions (cases 5-11 sunt identice, doar cheia variază)
- `TRUE_FALSE check` → 1 decision (cu 2 ternari)
- **Total: 14 decisions + 1 null check = 15**

**Esențială sau accidentală?** Complexitatea este accidentală: toate case-urile diferă doar printr-un table-lookup (grade3Key, grade4Key etc.), nu pe comportament real. Fiecare case verifică doar o intrare din map, nu rulează logică complexă.

## Task 2: A / I / D (Abstractness/Instability/Distance)

**Metrice pentru repo ETL-R (după modelul din `make-the-grade-pages`):**

| Componentă | A (Abstractness) | I (Instability) | D (Distance) | Zona |
|---|---|---|---|---|
| AutoGrading | 0.00 (toată implementația) | 0.14 | 0.86 | **Zone of Pain** — implementare puternic dependată; orice schimbare se propagă la 6 caller-i |
| QuestionSender | 0.25 | 0.60 | 0.15 | **Pass** — puține dependențe, stabil |
| AnswerCapture | 0.20 | 0.80 | 0.00 | **Pass** — foarte instabilă, dar cu abstractness scăzut |
| TestLoader | 0.38 | 0.40 | 0.22 | **Pass** — echilibrat |
| ReportingCore | 0.75 | 0.86 | 0.61 | **Fail** — Zone of Uselessness — în principal abstracții, puțin comportament, fan-out mare |

**Formule:**
- A = ΣNa / (ΣNa + ΣNc) — raport clase abstracte vs. total
- I = Ce / (Ce + Ca) — raport dependențe eferente vs. total
- D = |A + I − 1| — distanța față de main sequence (ideal: D < 0.3)

## Task 3: ADL cu praguri

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

## Task 4: Refactorizare pe main sequence

**Refactorizarea propusă:**
1. Extract Grader, AnswerKeyProvider și AnswerMatcher în interfețe separate
2. TrueFalseMatcher și MultipleChoiceMatcher rămân concrete
3. Încărcarea cheilor mutată la Answer Key Loader
4. După refactor: AutoGrading devine 2 clase abstracte + 3 concrete: A = 0.60. I rămâne 0.14. D = 0.26: **Pass**.

**Motiv:** Elimină "Zone of Pain" prin introducerea de abstracții la nivel de componentă, nu la nivel de metodă.