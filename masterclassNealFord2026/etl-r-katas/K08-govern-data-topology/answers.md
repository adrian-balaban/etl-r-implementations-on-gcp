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
- **Static analysis (Terraform/IAM + manifests):** Inspectează configurațiile de conectivitate ale serviciului de recon. Dacă recon-service are o dependență la `etlr_extraction` în manifest-ul Kubernetes, această aserțiune se va rupe în pull request sau la build, chiar înainte de deploy.
- **Dynamic analysis (Kafka flow):** Dacă recon consumă direct din topic-ul de intrare în loc să aștepte confirmările, flow-ul de date va diferi și `settle` nu va corela corect cu `written`+`rejected`.
- **Platform analysis (Cloud SQL logs):** Log-urile Cloud SQL vor evidenția noi conexiuni nedeclarate de la recon-service la schema `account_source`, semnalând depășirea cuantelor.

**Care prinde primul?** Analiza statică – este cea mai rapidă și cea mai deterministică. Dacă TDM (Terraform Dependency Monitor) detectează o legătură neautorizată, fitness function-ul pică la nivelul pull request-ului, înainte de orice test de runtime.

## Task 2: Căi legitime fără încălcarea topologiei

**Calea 1: Replicare nume de date (data schema replication)**
- Testing deține cache-uri read-only proprii cu numele din roster în Grading DB.
- Raporturile rulează după fereastra de test, așa că un mic lag e inofensiv.
- **Cost:** Date duplicate, dar supraviețuiește outage-urilor Testing.

**Calea 2: Data sidecar**
- Testing deține un cache simplu și consistent al numelor din roster.
- Dependența pe acest tip de cache supraviețuiește outage-urilor Testing.
- **Cost:** O dependență pe cache, dar izolată de dependențele de bază.

**Calea 3: Data-based payload contract**
- Answer Capture adaugă numele studentului fiecărui mesaj (numai pe cel mai ocupat path).
- Mesaje mari pe bus, dar fără "extra calls or stores".
- **Cost:** Mesaje mai mari pe cel mai ocupat path, dar izolat.

**Recomandare:** Replicarea numelor din roster în Grading DB, refresh-ată când roster-ul se schimbă. Raportările rulează după testing, așa că un lag mic e inofensiv.

## Task 3: Funcția fitness de reconciliere

**Ce se examinează și când:**
| Analiza | Ce inspectează | Când capturează schimbarea |
|---|---|---|
| **Static** | Terraform values + Helm config + manifest Kubernetes | În pull request sau la build, înainte de deployment |
| **Dynamic** | Service-mesh / Hubble flows: conexiuni deschise la DB | Doar după ce schimbarea rulează (la runtime) |
| **Platform** | Database-side connection logs, secrets-manager grants, DNS lookups | La runtime, din partea bazei de date |

**MCP tool specific ETL-R:** `get_balance(run_id)` – rulează când o fereastră de test se încheie, compară balanța `src_read = written + rejected` și alertează dacă nu se echilibrează.

## Task 4: Crash între dequeue și persist

**Asynchronous Chain of Persistence:**
1. **Synchronous send** – Answer Capture trimite mesajul pe Kafka topic `target-system-target` și așteaptă acknowledgmentul clientului (ack mode).
2. **Client acknowledge mode** – Clientul (Test System) confirmă mesajul numai după ce a persistat confirmarea.
3. **Last-participant support** – Dacă Grading e lent sau down, queue-ul protejează answer-urile prin persistare; nu se pierd niciodată.

**Pattern prevăzut de ETL-R:**
- `load` step publică la Kafka `target-system-target` cu idempotență pe account key.
- `recon` step verifică ecuația `src_read = written + rejected` contra confirmărilor din `settle`.
- Dacă `load` step pică (mock-ul picat), `recon` marchează run-ul ca `unsettled=N` + `RECONCILIATION FAILED`, dar nicio dată nu se pierde silențios – sunt în DLQ cu reason enumerat.