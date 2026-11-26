# Răspunsuri K11: Dynamic coupling și entanglement

## Task 1: Capability ADL pentru `produce` vs. `load`

**Definiția capability-urilor actuale din ETL-R:**

```
DEFINE CONST max_user_load AS 200000
DEFINE CONST avg_response_time AS 300ms  -- pentru Flink produce
DEFINE CONST avg_response_time AS 2000ms  -- pentru Auto Grading (mai lent)
```

**Conflictele cu chemarea sincronă:**
- `produce` are `max_user_load = 200,000` users, `avg_response_time = 300ms` (target)
- `load` are `max_user_load = 5,000` users (consumatorii Grading Service), `avg_response_time = 2000ms`
- Dacă `produce` apelează `load` sincron, răspunsul scade la limita celui mai lent: **5,000 users @ 2s**, nu la 200,000 users @ 300ms.
- **Dynamic quantum entanglement:** când serviços independente cheamă reciproc sincron, capacitatea operatională scade la cel mai mic în lanțul lanțul.

**Fitness function ADL:**
```
DEFINE COMMUNICATION produce TO load AS asynchronous
     VIA Kafka topic target-system-target
ASSERT(actual response time at max_user_load <= avg_response_time)
     AT max_user_load for produce service
```

## Task 2: `DEFINE COMMUNICATION` pentru fiecare relație

| Relație | Definitie ADL | Aserțiune |
|---|---|---|
| **orchestrator → gate** | `DEFINE COMMUNICATION Test Session TO Gate AS synchronous` | `ASSERT(Communication between services adheres to defined declarations)` |
| **produce → load** | `DEFINE COMMUNICATION produce TO load AS asynchronous` `VIA Kafka topic target-system-target` | `ASSERT(no quantum publishes a shared-database channel)` |
| **mock → recon** | `DEFINE COMMUNICATION Answer Capture TO Recon AS asynchronous` | `ASSERT(settle targetRows confirmed matches written + rejected)` |
| **enrich → target** | `DEFINE COMMUNICATION enrichment TO target AS asynchronous` `USING reference/branches.json` | `ASSERT(READONLYREPLICA is cached copy of reference data)` |

**Important:** Toate comunicările sunt **asincrone** pentru a permite answer-urile să supravegheze Grading outages and bursts, iar gradingul poate lag studentul de câteva secunde. Ordinea se menține doar în Testing, unde fiecare answer returnează imediat next question din cache local.

## Task 3: `enrich` cu date de referință

**Alternativă cu READONLYREPLICA:**
- `enrich` join-urile cu `reference/branches.json` (lookup table static).
- Poți modela ca `READONLYREPLICA branches AS branch-cache WITHIN Grading Quantum`.
- **Ce rămâne de cuplaj static:** Temporal coupling static parte: cache-ul este statice pe lângă quantum, dar după fiecare test window, cache-ul se reîncarcă înainte de a porni testul următor.
- **Ce pierde:** Nu mai ai accesul sincron la datele de referință în timpul testului; trebuie să pregări cache-ul înainte.

## Task 4: Ce pierde run-ul pe care Kafka-l protejează

**Fault tolerance la mock șteampilat:**
- Varianța reală: Target System mock-ul Kafka login-ul stăpânește după ~34 min.
- Conssequa: `load` step pică closed (`unsettled=N`, `RECONCILIATION FAILED`).
- **Ce se întâmplă cu Kafka:** În celelalte run-uri (flink-jdbc, beam-dataflow), mock-ul a picat și la ele, **niciun date s-a pierdut silențios** – run-urile au fost reciclând Cloud Run revision și retry-urile au trecut.
- **Ce lipsește fără Kafka:** Dacă producătorul e sincron (cum e cazul agent-ului de debugging), atunci când mock-ul pică, **toate înregistrările în tranzit se pierd** – nu există queue-ul durable ca protecție, nu există reconcilierie `src_read = written + rejected`, că producătorul a eroare degen pe rand.

**Pattern-ul ETL-R:** Kafka oferă un buffer durabil; dacă producerul pică, mesajele stau în queue până când consumatorul revine online. Sincronizarea pierde această durabilitate.