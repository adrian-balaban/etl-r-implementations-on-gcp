# Răspunsuri K06: FFDA - Fitness Function Driven Architecture

## Task 1: Alege load-bearing funcții

| Candidat | Verdict | Motiv |
|---|---|---|
| **1. Answer Capture accepts 200,000 concurrent submissions within 300 ms** | **Load-bearing** | Protegează cerința de scalabilitate definită de business (200k students) |
| **2. No submitted answer is lost, even while Grading is down** | **Load-bearing** | Protejează cerința "no data loss" – o consecință directă a cerințelor educaționale |
| **3. The answer database never exceeds 300 connections** | **Load-bearing** | Capacitate rezervată de infrastructură (cost + performanță) |
| **4. Answer keys are unreachable from student-facing services** | **Load-bearing** | Securitate + separarea domain-ului (Conway's Law) |
| **5. Every class has a unit test** | **Reject** | Concern de unit-testing — nu e arhitectură; agenții nu au nevoie de așa ceva |
| **6. Every method has cyclomatic complexity under 5** | **Reject** | Prea fragil la nivel de sistem; aplică ca metric gate pe pachetele generate de agent |
| **7. The admin UI uses the approved color palette** | **Reject** | Linter sau check ingineresc: nu e arhitectură, e stil |
| **8. Report PDFs generate in under 10 seconds** | **Not now** | Real, dar secundar; adăugați mai târziu dacă userii raportează lentoare |
| **9. Every service logs in JSON** | **Reject** | Linter/style check: zgomot, nu e arhitectură |
| **10. The test scheduling screen loads in under 1 second** | **Not now** | Real, dar secundar, pentru UX, nu e load-bearing |

**Regula de aur:** Păstrează doar elementele care susțin greutatea structurii (load-bearing), nu "tapetul". O acoperire completă cu funcții create de falsAlarm-uri îi învață pe oameni să ignore eșecurile.

## Task 2: Scrie-le în ADL înainte de cod

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

**Răspunsul:** **Funcția 1** – "200,000-submission load test". Scrieți-o prima și așteptați să eșueze săptămâni întregi, până când echipa găsește un design care trece; tot restul e secundar.

De aceea, în repo-ul ETL-R, **balanța (`src_read = written + rejected`)** a fost prima funcție scrisă (poți vedea `make spec-test` care o validează rând-cu-rând).

## Task 4: "Cost/run" pică în săptămâna 4 → nu-i bug

**Dispoziția (regula cursului):**
- **Fix:** Dacă e o schimbare de design legitimă (de exemplu, optimizarea pipeline-ului pentru a reduce timpul de start-up)
- **Revise:** Dacă e necesară o revizuire a topologiei (de exemplu, schimbarea numărului de worker-uri Dataflow)
- **Exemption** cu owner și termen: "Cost/run pică deși avem feature X încă nefinalizat" → scutire explicită, niciodată ignorată, owner: "Platform Team", expiry: "next quarter review"

---
*Generat în cadranul K06 – FFDA*