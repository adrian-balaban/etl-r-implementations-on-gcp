# Răspunsuri K15: Schimbare constantă: stressori și rezidii

## Task 1: Completează matricea de stressori

| Stressor | S1 (Proiect nou) | S2 (GCP price/Degradează Kafka) | S3 (20M înregistrări) | S4 (Target API v2) | S5 (Regiune/Residency) | S6 (4 implementare) |
|---|---|---|---|---|---|---|
| **Spec / PIPELINE.md** | ✅ Noi mapping + revalida conformance | ⚠️ Poate necesita update config | ✅ Poate necesita mici ajustări | ⚠️ Schimbă schema + revalida | ⚠️ Poate necesita multi-region setup | ❌ Nu e compatibil cu spec existent |
| **Contracte (artefacts.json, mappings)** | ✅ Noi mapping-uri | ✅ Poate necesita update config | ✅ Stabile (mapping volatil) | ✅ Schimbă contract JSON Schema | ✅ Poate necesită region-specific config | ❌ Divergențe între implementări |
| **Flink (SQL/DataStream)** | ⚠️ Necesită testee noi | ⚡ Varianța Kafka ±10 min | ✅ Scalabil la 200k | ⚠️ Schimbă schema target | ✅ Multi-region possiv | ❌ Nici nativ |
| **Flink-jdbc** | ✅ Pin mapping + verify-project2 | ⚡ Varianța Kafka ±10 min | ✅ Celer pe 200k (load pre-run) | ⚠️ Schimbă source DB | ✅ Poate migra Postgres local | ❌ Nici nativ |
| **Beam Dataflow** | ⚠️ Noi mapping-uri + generate | ⚡⚡ Varianța ±20 min (Composer+Kafka) | ✅ Poate scale dar costul 2.5× | ⚠️ Schibge contract Kafka | ✅ Multi-region BigQuery | ⚠️ Poate fi adăugat ca variantă |
| **Harness / generate.py** | ✅ Fișiere noi seeded | ✅ Stabile | ✅ Identic (seeded) | ✅ Stabile | ✅ Stabile | ❌ Re-quirer generate rewrite |
| **Recon / balance** | ✅ Egal (55=50+5) | ✅ Egal | ✅ Egal (200k=200k+5) | ⚠️ Confirmations schimbă | ✅ Egal | ❌ Topologie differentă |

**Analize:**
- **S1** affectează spec și contracte – reziduul e `ASSERT(adding a project modifies only spec/contracts/)`.
- **S2** affectează varianța Kafka – reziduul e MCP tool `get_cost_last_run()` + monitorizare a stării Kafka.
- **S3** affectează scalabilitate – reziduul e fitness function cost/ runtime limite.
- **S4** affectează schema – reziduul e ADL `ASSERT(JSON Schema validates target document structure)`.
- **S5** affectează regiunea – reziduul e configurarea multi-region în ADL.
- **S6** affectează topologia – reziduul e `ASSERT(Do not allow static coupling between quanta not specified here)`.

## Task 2: Proiectează rezidii

**Residii identificate:**
1. **Contracts-as-config:** `artefacts.json` + mapping-urile YAML sunt configurarea centralizată – schimbările here sigură, nu_code changes.
2. **Oracle pinned:** `manifest.json` + `ACCOUNT.src` fixed SHA-256 – asigură că oracolul nu driftă.
3. **Stage-mapping abstract:** `08-stage-mapping.svg` – definirea etapelor la nivel abstract, nu implementare-specific.

**Prioritate:**
- **Business zone:** S1 și S4 sunt cele mai critice (schimbări care affectează conformance direct).
- **# stressori acoperiți:** S1, S4, S6 acoperă 3/6 stressori "esențiali".
- **Probability × impact:** S1 (proiect nou, near-certain), S4 (API change, likely), S2 (Kafka variance, possible but high impact).

## Task 3: ADL pentru modificarea proiectului

```
ASSERT(adding a project modifies only spec/contracts/)
ASSERT(manifest.json sha256 pinned: ACCOUNT.src unchanged)
ASSERT(stage mapping abstract: 08-stage-mapping.svg unchanged modulo aditions vizuale)
```

**Sfat:** Fiecare schimbare nouă în `implementations/` care nu e doprintă în `spec/contracts/` va pică fitness function-ul `ASSERT(adding a project modifies only spec/contracts/)`, asigurând că agentii nu o introduc ușor.

## Task 4: Regulă de aur a cursului

**Stressori** care affectează direct business capabilities trebuie mapate la ADL assertions, iar **rezidii** (contracts-as-config, oracle pinned, stage-mapping abstract) sunt preluate ca gardătoare anticiptate, nu reacțiți la fiecare schimbare.