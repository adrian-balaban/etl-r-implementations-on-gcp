# Răspunsuri K15: Schimbare constantă: stressori și reziduuri

## Task 1: Completează matricea de stressori

| Stressor | S1 (Proiect nou) | S2 (GCP price/Degradează Kafka) | S3 (20M înregistrări) | S4 (Target API v2) | S5 (Regiune/Residency) | S6 (4 implementare) |
|---|---|---|---|---|---|---|
| **Spec / PIPELINE.md** | ✅ Noi mapping + revalidare conformance | ⚠️ Poate necesita update config | ✅ Poate necesita mici ajustări | ⚠️ Schimbă schema + revalidează | ⚠️ Poate necesita multi-region setup | ❌ Nu e compatibil cu spec existent |
| **Contracte (artefacts.json, mappings)** | ✅ Noi mapping-uri | ✅ Poate necesita update config | ✅ Stabile (mapping volatil) | ✅ Schimbă contractul JSON Schema | ✅ Poate necesita configurare region-specific | ❌ Divergențe între implementări |
| **Flink (SQL/DataStream)** | ⚠️ Necesită teste noi | ⚡ Varianța Kafka ±10 min | ✅ Scalabil la 200k | ⚠️ Schimbă schema target | ✅ Multi-region posibil | ❌ Nu e suportat nativ |
| **Flink-jdbc** | ✅ Pin mapping + verify-project2 | ⚡ Varianța Kafka ±10 min | ✅ Rapid pe 200k (load pre-run) | ⚠️ Schimbă source DB | ✅ Poate migra Postgres local | ❌ Nu e suportat nativ |
| **Beam Dataflow** | ⚠️ Noi mapping-uri + generate | ⚡⚡ Varianța ±20 min (Composer+Kafka) | ✅ Poate scala, dar costul 2.5× | ⚠️ Schimbă contractul Kafka | ✅ Multi-region BigQuery | ⚠️ Poate fi adăugat ca variantă |
| **Harness / generate.py** | ✅ Fișiere noi seeded | ✅ Stabile | ✅ Identic (seeded) | ✅ Stabile | ✅ Stabile | ❌ Necesită rescrierea lui generate.py |
| **Recon / balance** | ✅ Egal (55=50+5) | ✅ Egal | ✅ Egal (200k=200k+5) | ⚠️ Confirmations schimbă | ✅ Egal | ❌ Topologie diferită |

**Analize:**
- **S1** afectează spec și contracte – reziduul e `ASSERT(adding a project modifies only spec/contracts/)`.
- **S2** afectează varianța Kafka – reziduul e MCP tool `get_cost_last_run()` + monitorizare a stării Kafka.
- **S3** afectează scalabilitate – reziduul e fitness function cost/ runtime limite.
- **S4** afectează schema – reziduul e ADL `ASSERT(JSON Schema validates target document structure)`.
- **S5** afectează regiunea – reziduul e configurarea multi-region în ADL.
- **S6** afectează topologia – reziduul e `ASSERT(Do not allow static coupling between quanta not specified here)`.

## Task 2: Proiectează reziduuri

**Reziduuri identificate:**
1. **Contracts-as-config:** `artefacts.json` + mapping-urile YAML sunt configurarea centralizată – schimbările aici sunt sigure, nu code changes.
2. **Oracle pinned:** `manifest.json` + `ACCOUNT.src` fixed SHA-256 – asigură că oracolul nu driftă.
3. **Stage-mapping abstract:** `08-stage-mapping.svg` – definirea etapelor la nivel abstract, nu implementare-specific.

**Prioritate:**
- **Business zone:** S1 și S4 sunt cele mai critice (schimbări care afectează conformance direct).
- **# stressori acoperiți:** S1, S4, S6 acoperă 3/6 stressori "esențiali".
- **Probability × impact:** S1 (proiect nou, near-certain), S4 (API change, likely), S2 (Kafka variance, possible but high impact).

## Task 3: ADL pentru modificarea proiectului

```
ASSERT(adding a project modifies only spec/contracts/)
ASSERT(manifest.json sha256 pinned: ACCOUNT.src unchanged)
ASSERT(stage mapping abstract: 08-stage-mapping.svg unchanged modulo adiții vizuale)
```

**Sfat:** Fiecare schimbare nouă în `implementations/` care nu e declarată prin `spec/contracts/` va pică fitness function-ul `ASSERT(adding a project modifies only spec/contracts/)`, asigurând că agenții nu o ocolesc ușor.

## Task 4: Regulă de aur a cursului

**Stressors** care afectează direct business capabilities trebuie mapați la aserțiuni ADL, iar **reziduurile** (contracts-as-config, oracle pinned, stage-mapping abstract) sunt preluate ca gardieni anticipați, nu ca reacții la fiecare schimbare.