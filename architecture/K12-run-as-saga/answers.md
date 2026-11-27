# Răspunsuri K12: Run-ul ca saga

## Task 1: Cele 3 forțe

| Forță | Definiție ADL | Argumente pe cerințe ETL-R |
|---|---|---|
| **Communication** | `DEFINE COMMUNICATION produce TO load AS asynchronous` `VIA Kafka topic target-system-target` | Topic-urile Kafka sunt durable; răspunsul nu e necesar imediat; answer-urile curg durabil; orice service poate consuma la propriul ritm. |
| **Coordination** | `DEFINE COORDINATION Orchestrated` `DEFINE SAGA Migration Run Saga` | Cloud Run job-ul (sau Composer DAG-ul) e orchestratorul; știe când queue-ul e gol și deține gestionarea erorilor.
| **Consistency** | `DEFINE CONSISTENCY Eventual` `ASSERT(settle targetRows confirmed matches written + rejected)` | "No answer lost" e durability, nu atomicity; grades și rapoartele pot întârzia câteva minute, dar toate sunt confirmate în final.

**Sinteză pe cerințe:**
- **Communication** asigură că mesajele nu se pierd – topic-urile Kafka stau în buffer.
- **Coordination** (Orchestrated) spune cine deține responsabilitatea opririi/rezolvării erorilor.
- **Consistency** (Eventual) confirmă că "no lost answers" e o proprietate de durabilitate, nu atomicitate.

## Task 2: "Atomic + choreography" devine ce?

**Ce devine în realitate:** Nu există un singleton care să stocheze starea tranzacției întregii 8 stațiuni. Fiecare service stochează partea sa (Flink: JobManager; Beam: Dataflow job state). Dacă vrei să vezi starea globală **trebuie să adaugi un service** care să o urmărească — acela e orchestratorul.

**De ce e neguvernabil?** Fără un orchestrator explicit:
- Fiecare service are o porțiune a stării (ex: `produce` ține evidența a cât scrie, `load` ține evidența a cât a confirmat).
- Nu există nicio entitate care să vadă starea totală (`src_read = written + rejected`).
- Un teammate care vrea "atomic consistency" creează efectiv un **orchestrator sub alt nume** – adică o schimbare de topologie, nu o soluție la problemă.

**Rezultat:** "Atomic + choreography" devine Orchestrator pattern cu un service dedicat (Cloud Run job sau Composer DAG).

## Task 3: ADL: `DEFINE SAGA Migration Run Saga`

```
DESCRIPTION Define Migration Run Saga for ETL-R
CATEGORY Integration
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE SAGA Migration Run Saga AS
          DEFINE COMMUNICATION Test Session TO Answer Capture AS asynchronous
          DEFINE COMMUNICATION Answer Capture TO Auto Grading AS asynchronous
          DEFINE COMMUNICATION Auto Grading TO Reporting AS asynchronous
          DEFINE COORDINATION Orchestrated
          DEFINE CONSISTENCY Eventual
     DEFINE CONTRACT graded-answer AS NAME-VALUE PAIRS
          INCLUDING {student-id, test-id, question-num, answer, receipt-id}
ASSERT(Communication between services matches the defined communication declarations)
ASSERT(Workflow is managed by the Test Session orchestrator)
```

**Contractul `graded-answer`:** Nume strict (NU protocol), numai câmpurile necesare ({student-id, test-id, question-num, answer, receipt-id}). Fiecare implementare le generează conform propriilor tehnologii, alegându-și protocolul (gRPC/REST/Kafka) – consumer-driven contract tests.

## Task 4: Scope de regenerare = cuanta / job-ul

| Guardrail | Verificare |
|---|---|
| **Behavior** | Functional tests: grading results, edge cases, the answer contract (conformance 68/68) |
| **Static coupling** | No new coupling to other quanta; Grading DB access unchanged |
| **Dynamic coupling** | Still consumes answers asynchronously; no new synchronous calls |
| **Contract** | graded-answer still accepts the required fields {student-id, test-id, question-num, answer, receipt-id} |
| **Saga** | Still participates in Migration Run Saga under the orchestrator |
| **Capability** | Still meets 5,000 users at 2s (sau 200,000 la Flink) |

**Rolul cuantei:** Scope-ul de regenerare e exact o cuantă. Dacă un agent modifică `spec/contracts/` (ex: adaugă un câmp nou la `graded-answer`), trebuie să treacă fitness function-urile de static coupling și de contract înainte ca build-ul să meargă.

---
*Generat în cadranul K12 – Run-ul ca saga*