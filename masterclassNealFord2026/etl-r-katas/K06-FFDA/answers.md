# Răspunsuri K06: FFDA - Fitness Function Driven Architecture

## Task 1: Alege load-bearing funcții

| Candidat | Verdict | Motiv |
|---|---|---|
| **1. Answer Capture accepts 200,000 concurrent submissions within 300 ms** | **Load-bearing** | Protegează cerința de scalabilitate definită de business (200k students) |
| **2. No submitted answer is lost, even while Grading is down** | **Load-bearing** | Protectă definea cerință "no data loss" – consecință regulată a cerințelor educaționale |
| **3. The answer database never exceeds 300 connections** | **Load-bearing** | Capacitate rezervată de infraestructură (cost + performanță) |
| **4. Answer keys are unreachable from student-facing services** | **Load-bearing** | Securitate + separarea domain-ului (Conway's Law) |
| **5. Every class has a unit test** | **Reject** | Concern de unit-testing, agentii ne au nevoie de atît; umani de umani |
| **6. Every method has cyclomatic complexity under 5** | **Reject** | Too brittle system-wide; apply to agent-generated packages as metric gate |
| **7. The admin UI uses the approved color palette** | **Reject** | Linter sau check ingineresc: nu e arhitectură, e style |
| **8. Report PDFs generate in under 10 seconds** | **Not now** | Real secundar; adăugați mai târziu dacă useri raportă slowness |
| **9. Every service logs in JSON** | **Reject** | Linter/style check: zgomot, nu e Arhitectură |
| **10. The test scheduling screen loads in under 1 second** | **Not now** | Real secundar, pentru UX, nu e load-bearing |

**Regula de aur:** Guardă doar elementele care suportă greutatea structurii (load-bearing), nu "tapetul". Cobertura completă a funcțiilor create de falsAlarm-uri care învăță oamenii să ignore eșecurile.

## Task 2: Scrie-le în ADL năprins de cod

Repo-ul ETL-R are deja balanța în `README.md` (numere reale măsurate pe GCP). Funcțiile load-bearing scrise în ADL:

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

Acestea sunt primele 4 funcții scrise, **înainte de cod**, în stilul LMAX (first to pass).

## Task 3: Care se scrie primul (stil LMAX)

**Răspunsul:** **Funcția 1** – "200,000-submission load test". Așteptați să eșueze săptămâni de timp până teamul găsește o design care trece; tot restul e secundar.

De aceea, în repo-ul ETL-R, **balanța (`src_read = written + rejected`)** a fost prima funcție scrisă (poți vedea `make spec-test` care o validează rând-cu-rând).

## Task 4: "Cost/run" pică în săptămâna 4 → nu-i bug

**Dispoziția (regula cursului):**
- **Fix:** Dacă e o schimbare de design legitimate (de exemplu, optimizează pipeline-ul pentru a reduce timpul de start-up)
- **Revise:** Dacă e necesară o revizuire a topologiei (de exemplu, schimbarea numărului de worker-uri Dataflow)
- **Exemption** cu owner și termen: "Cost/run pică deși avem feature X încă nefinalizat" → dispariție explicită, never ignored, owner: "Platform Team", expiry: "next quarter review"

---
*Generat în cadranul K06 – FFDA*