# Răspunsuri K17: Agenți și judecată delegată

## Task 1: Scope generare = o cuantă, nu "refactor tot"

**Definiția corectă:**
- Agentul are scope-ul limitat la o implementare (o cuantă), nu la refactorizarea tuturor implementărilor sau a spec-ului.
- ADL include `DEFINE AGENT Spark Builder AS spark-agent` împreună cu aserțiuni restrictive.

**Aserțiuni care limitează scope-ul:**
```
DEFINE AGENT Spark Builder AS spark-agent
     DEFINE COMPONENT SparkSubmit AS spark-submit
     DEFINE SERVICE SparkCluster AS spark-cluster
     ASSERT(agents cannot add cross-implementation dependencies)
     ASSERT(agents cannot add undeclared database connections)
     ASSERT(agent-generated changes stay within declared structural and operational boundaries)
```

**Motiv:** Dacă scope-ul e global, agentul poate crea, din greșeală, dependențe care rup `ASSERT(no cycles exist between services in ETL-R)` sau `ASSERT(Do not allow static coupling between quanta not specified here)`.

## Task 2: Verificarea deterministă vs. LLM-judge

**De ce determinismul e prioritar:**
- **Conformance 68/68** vs. oracolul e verificare obiectivă, deterministă, care rulează `make spec-test` / `make conformance-all`.
- **LLM-judge** e subiectiv, non-determinist, și trebuie folosit doar ca guardrail interimar, nu ca build gate.

**Unde rămâne judecata umană:**
- **Probe random:** selectează rânduri din output-ul agentului pentru verificarea vizuală a calității.
- **Review uman:** un procentaj specificat de eseuri (ex: 2% din notele finale la eseuri) verificate de oameni reali.
- **Rata recomandată:** Checklist-ul cursului spune: "2% spot checks of final essay grades" + "human reviews SPOT_CHECK_RATE of final essay grades".

**Pattern ETL-R:**
- Build-ul blochează dacă `make conformance` pică (determinist).
- LLM-judge rulează doar "on-demand" sau pentru a genera documente, nu ca gate în CI/CD.

## Task 3: `reject_log` = DLQ complet

**Componenta esențială care nu se poate automatiza complet:**
1. **Reason-uri enumerate:** `PARSE_BAD_COLUMN_COUNT`, `PARSE_BAD_NUMERIC`, `PARSE_BAD_DATE`, `MAP_UNMAPPED_ENUM_VALUE`, `SCHEMA_INVALID` – nu text liber.
2. **Calea umană:** `record_lineage` tracează originea fiecărei înregistrări plasate în DLQ – necesară când agentul nu poate determina cauza rădăcină.
3. **Fitness function:** `ASSERT(when the answer queue is drained, every answer receipt in Test Database has a graded answer in Grading Database)` – deterministică, nu LLM.
4. **Ultima milă umană:** Dacă DLQ-ul nu e gol după N minute de test, un operator revizuiește manual erorile și decide dacă e cazul de a repara mapping-ul sau să accepte defectul.

**Rețeta completă:**
- `reject_log` cu coloane: `run_id`, `account_key`, `reason` (enumerat), `detail` (literal ca la contract).
- `record_lineage` cu: `run_id`, `account_key`, `source_key` (raw:<hash>).
- Fitness function rulabil: `get_queue_depth()` + `get_graded_key_hash()` rulează la încheierea ferestrei de test.
- Cale umană: dacă DLQ-ul nu e gol după 5 minute, un operator face review manual și acționează.

## Task 4: MCP server la governance context-ul ETL-R

**Metrice ce rulează pe MCP server:**
- `get_balance(run_id)` – rulează când o fereastră de test se încheie, compară `src_read = written + rejected`.
- `get_conformance()` – rulează `make conformance-all` și returnează %-ul de trecere vs. oracol.
- `get_cost_last_run(run_id)` – măsoară cost-ul real pe GCP și îl compară cu pragurile ADL.

**Cine le rulează și cu ce cadență:**
- **CI/CD:** `get_conformance()` rulează la fiecare build/PR (determinist, build gate).
- **Lunar:** `get_cost_last_run()` rulează lunar, alertează dacă costurile depășesc pragurile ADL.
- **După fiecare test window:** `get_balance(run_id)` rulează instant, iar dacă nu se echilibrează, alertează team-ul imediat.

**Recomandare:** Toate trei funcțiile rulează automat, dar doar `get_conformance()` e gate-ul build-ului. Celelalte două sunt alerte, nu blochează build-ul.