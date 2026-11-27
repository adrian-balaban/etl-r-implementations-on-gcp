# Răspunsuri K09: Mediul de business și cost

## Task 1: Răspuns la fiecare scenariu

| Scenariu | Flink | Flink-jdbc | Beam Dataflow | Riscul |
|---|---|---|---|---|
| **S1: Proiect nou fiecare săptămână (DEPOSIT)** | ⚠️ Modificare mapping + revalidare conformance | ✅ Pin mapping + rulează `verify-project2` | ⚠️ Noi mapping-uri + rulează beam generate | Riscul: testarea echivalenței `project2` vs `project1`; `project2` nu moare — și nici nu are voie să moară |
| **S2: GCP schimbă prețul sau degradează Managed Kafka** | ⚡ Timp ramp-up ±10 min (Kafka variance) | ⚡ Timp ramp-up ±10 min (Kafka variance) | ⚡⚡ Timp ramp-up ±20 min (Composer + Kafka) | Riscul: costurile de runtime cresc; monitorizează cu unealta MCP `get_cost_last_run()` |
| **S3: 20M înregistrări pe run** | ✅ Fiecare job are cost fix de start-up | ✅ Cel puțin costuri fixe mai mici | ❌ Composer $1.03 fixed + 2.5× costuri | Riscul: Beam devine 5× mai scump; migrează la Flink pentru volume mare |
| **S4: Target System API v2 la confirmations** | ⚠️ Schimbă schema + revalidează conformance | ⚠️ Schimbă source database + migrare | ⚠️ Schimbă contractul Kafka + redeploy | Riscul: `SCHEMA_INVALID` la load; `MAP_UNMAPPED_ENUM_VALUE` la map; monitorizează contractul |
| **S5: Altă regiune/residency** | ⚠️ Replică infrastructura GCP | ✅ Sursele Postgres pot fi locale | ⚠️ Composer + BigQuery multi-regiune | Riscul: latență cross-region; folosește BigQuery multi-regiune |
| **S6: Se cere o a 4-a implementare (Spark/Dataproc)** | Nu e suportat nativ | Nu e suportat nativ | ⚠️ Poate fi adăugat ca variantă beam | Riscul: Nu e Architecture as Code – e "new tool" fără spec |

**Concluzie:** Pentru majoritatea scenariilor, **Flink** și **Flink-jdbc** sunt alegerea defensivă; Beam e justificat doar pentru workload-uri mici sau când costul nu e factorul principal.

## Task 2: Cost ADL pe 2 $-grade

Definiția costurilor reale pe GCP (din `cost-estimate.md` și `docs/evidence/README.md`):
- **Flink:** $0.90 pe run (resource count 71, total 59 min 31 s)
- **Flink-jdbc:** $0.94 pe run (resource count 81, total 56 min 45 s)
- **Beam Dataflow:** $2.33 pe run (resource count 75, total 2 h 31 min)

**Formule ADL:**
```
DEFINE CONST COST_FLINK AS 0.90
DEFINE CONST COST_FLINK_JDBC AS 0.94
DEFINE CONST COST_BEAM AS 2.33
DEFINE CONST COST_RATIO_BEAM_TO_FLINK AS COST_BEAM / COST_FLINK  ≈ 2.59
ASSERT(Total GCP cost per run for flink <= COST_FLINK + ε)
ASSERT(Total GCP cost per run for flink-jdbc <= COST_FLINK_JDBC + ε)
ASSERT(Total GCP cost per run for beam <= COST_BEAM + ε)
ASSERT(COST_RATIO_BEAM_TO_FLINK > 2.0  ➡  Beam costă peste 2 ori mai mult decât Flink)
```

**Monitorizare MCP:** `get_cost_last_run(run_id)` – rulează la fiecare run, compară costul real cu pragurile ADL și alertează dacă le depășește.

## Task 3: Recomandare defensivă + ADL de revenire

**Recomandare:** O combinație **Flink (pentru performanță și cost)** + **o variantă de streaming (pentru scalabilitate eventuală)**.

**ADL de revenire (reconciliation fitness function) care monitorizează riscul:**
```
DEFINE CONST MAX_RUNTIME_FLINK AS 60min
DEFINE CONST MAX_RUNTIME_BEAM AS 180min
DEFINE CONST MAX_COST_FLINK AS 1.00  # margine 10% peste $0.90
DEFINE CONST MAX_COST_BEAM AS 2.60    # margine 10% peste $2.33

ASSERT(actual_runtime_flink_run_<run_id> <= MAX_RUNTIME_FLINK)
ASSERT(actual_cost_flink_run_<run_id> <= MAX_COST_FLINK)
ASSERT(no submitted answer is lost while Grading is unavailable)
ASSERT(Answer Database never exceeds MAX_DB_CONNECTIONS connections)
```

Acest fitness function rulează periodic (fie lunar, fie după fiecare run mare) și alertează echipa dacă costurile sau runtime-ul depășesc limitele acceptabile, permițând o intervenție înainte ca costurile să devină incontrolabile.

---
*Generat în cadranul K09 – Mediul de business și cost*