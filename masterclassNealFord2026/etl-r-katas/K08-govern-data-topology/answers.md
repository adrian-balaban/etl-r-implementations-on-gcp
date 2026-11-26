# Răspunsuri K08: Guvernarea topologiei

## Task 1: Care aserțiune se rupe

Agentul care "grăbește recon-ul" și își conectează serviciul de recon direct la `etlr_extraction` (în loc să execute recon specificată) va rupe aserțiunea:

```
ASSERT(Test Database is only accessed by Testing Domain Services)
ASSERT(Grading Database is only accessed by Grading Domain Services)
ASSERT(Admin Database is only accessed by Administration Domain Services)
ASSERT(Do not allow static coupling between quanta not specified here)
```

**Care e testul pe care îl pică:**
- **Static analysis (Terraform/IAM + manifests):** Inspectează configurațiile de legătură a serviciului de recon. Dacă recon-service are o dependență la `etlr_extraction` în manifest-ul Kubernetes, această aserțiune se va rupe în pull request sau build, înainte chiar și de deploy.
- **Dynamic analysis (Kafka flow):** Dacă recon consumă direct din topic-ul de intrare în loc să așteaptă confirmările, flow-ul de date se va diferenția și `settle` nu va corelate corect cu `written`+`rejected`.
- **Platform analysis (Cloud SQL logs):** Log-uri Cloud SQL vor muestra noi conexiuni nedeclare de la recon-service la schema `account_source`, flagând depășirea cuantelor.

**Care prinde primul?** Analiza statică – este cea mai rapidă și cea mai deterministică. Dacă TDM (Terraform Dependency Monitor) detectează o legătură neautorizată, fitness function-ul pică la level-ul pull request-ului, înainte de orice test al runtime-ului.

## Task 2: Căi legitime fără încălcarea topologiei

**Calea 1: Replicare nume de date (data schema replication)**
- Testarea proprie o cache-uri read-only ale numelor roster-ului în Grading DB.
- Raporturile rulează după fereastra de test, așa că un mic lag e inofensiv.
- **Cost:** Duplicate date, dar survie Testing outages.

**Calea 2: Data sidecar**
- Testing propriește un cache simplu și consistent al numelor roster-ului.
- Dependency pe cache-ul acestui tip survie outage-urile Testing.
- **Cost:** Un dependency pe cache, dar izolată de dependențele de bază.

**Calea 3: Data-based payload contract**
- Answer Capture adaugă numele studentului fiecărei mesaje (numai pe cel mai ocupat path).
- Mesaje mari pe bus, dar nu există "extra calls or stores".
- **Cost:** Larger messages pe cel mai ocupat path, dar izolat.

**Recomandare:** Replicarea numelor de rosters în Grading DB, refresh-ate când rosters-ul se schimbă. Raporturile rulează după testing, așa că un lag mic e inofensiv.

## Task 3: Funcția fitness de reconciliere

**Ce se examinează și când:**
| Analiza | Ce inspeționează | Când capturează schimbarea |
|---|---|---|
| **Static** | Terraform values + Helm config + manifest Kubernetes | În pull request sau build, înainte deployment |
| **Dynamic** | Service-mesh / Hubble flows: conexiuni deschise la DB | Doar odată ce schimbarea rulează (la runtime) |
| **Platform** | Database-side connection logs, secrets-manager grants, DNS lookups | La runtime, de la latură baze de date |

**MCP tool specific ETL-R:** `get_balance(run_id)` – rulează când o fereastra de test se încheie, compară balanța `src_read = written + rejected` și alertează dacă nu se echilibrează.

## Task 4: Crash între dequeue și persist

**Asynchronous Chain of Persistence:**
1. **Synchronous send** – Answer Capture trimite mesajul pe Kafka topic `target-system-target` și așteaptă acknowledgmentul clientului (ack mode).
2. **Client acknowledge mode** – Clientul (Test System) acknowledge mesajul numai după ce a persistența confirmării.
3. **Last-participant support** – Dacă Grading este încet sau down, queue-ul protejează answer-urile prin persistare; nu se pierd niciodată.

**Pattern prevăzut de ETL-R:**
- `load` step publish la Kafka `target-system-target` cu idempotent pe account key.
- `recon` step balancează ecuatia `src_read = written + rejected` contra confirmărilor din `settle`.
- Dacă `load` step pică (mock șteampilat), `recon`ărăfiere run-ul ca `unsettled=N` + `RECONCILIATION FAILED`, dar niciun date se pierd silențios – sunt în DLQ cu reason enumerat.