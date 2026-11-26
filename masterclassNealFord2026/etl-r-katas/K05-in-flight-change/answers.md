# Răspunsuri K05: Schimbare în zbor

## Task 1: Branch vs. toggle vs. config

| Strategie | Când e alegere | Pentru cele 3 implementări |
|---|---|---|
| **Branch** | Feature development care necesită teste separate, unde cadere nu afectează main | Fiecare implementare are propriul `Makefile` + `make conformance`; branch-uri separate sunt acceptabile pentru testarea paralelă |
| **Toggle** | Schimbă behavior la runtime fără deploy nou; good pentru funcționalități opt-in/opt-out | Nu e caz de folosință pentru ETL-R (schimbările de mapping sunt de deploy, nu runtime) |
| **Config (mapping-YAML)** | Schimbă transformarea fără schimba cod; mapping-ul e deja fișier configurabil | **Toate implementările** citesc `mappings/*.yaml` din `spec/contracts/` la runtime (Python) sau build time (Java). Schimbarea mapping-ului e schimbarea config-ului, nu a codului. |

**Concluzie:** Pentru `mask_emails` sau orice nou câmp, **folosiți config (mapping-YAML)**. Nu e nevoie de tușeți în cod nici pentru o implementare, să Pă îmbunătățiți contractul centralizat `artefacts.json` sau mapping-ul YAML.

## Task 2: Classifică contractele

| Contract | Strict sau Permeabil | Argument |
|---|---|---|
| **mapping.yaml** | **Permeabil** pentru enum values (A↔C↔D), **Strict** pentru tipuri w/ protocol | `STATUS=X` este rejectat ca `MAP_UNMAPPED_ENUM_VALUE` – nu ghicirea, ci maparea nereușită. Protocolul (gRPC/REST) e Strict doar dacă e necesar pentru interoperabilitate. |
| **JSON Schema** | **Strict** – validare structură document țintă | `target schema` definește coloanele wajib (`accountId`, `STATUS`, `BALANCE`) și tipurile de date. Fiecare lipsă ⇒ `SCHEMA_INVALID`. |
| **Protocol (gRPC/REST)** | **Strict numai dacă e nevoie** | `USING gRPC` în contract înseamnă că teamii trebuie să folosească gRPC. Fără argumente, linter-ul nu enforcează protocolul. |
| **Artefact names (.DAT/.CHS)** | **Strict** – nume standardizate din `artefacts.json` | Orice dezviere ⇒ `make spec-test` pică pe `PARSE_MISSING_FIELD` sau `SCHEMA_INVALID`. |

## Task 3: Numărul maxim de versiuni

Topic-ul Kafka `target-system-target` are o versiune majoră impusă de schema JSON. Fiecare implementare suportă:
- **Flink (SQL)**: 1 versiune activă, schimbarea schema-ului pică conformance dacă nu actualizează `target schema`
- **Flink-jdbc**: 1 versiune, Schema strict deoarece bazei de date enforced types
- **Beam Dataflow**: 1 versiune, Composer managed – schimbarea topic-ului necesită DAG restart

**Limita:** Nu există restricție strictă de număr de versiuni, dar `MAX_ENDPOINTS = 10` ADL aserțiune previne sprawl-ul versiunilor.

## Task 4: ADL anti-tautologie

```
ASSERT(FOREACH TEST t IN all tests DO ASSERT(t contains at least one assertion) END)
```

**Rețeta agentului:** Un agent ar putea genera `ASSERT(true)` ca o "assertion", care e tautologie. ADL correctează prin:
- Verificarea că assertion-ul conține o comparație reală sau o predicat
- `FOREACH ASSERT a IN test DO ASSERT(a is not tautology) END` (pseudocod)
- Rulează fitness function la fiecare test additionat: dacă build-ul pică pentru "no assertions", agentul a adăugat un test gol

**Prevenirea:** Fișierul `make-the-grade-pages-*/answers.md` are task-uri specifice pentru a identifica și rezista rețetelor agentului.

---
*Generat în cadranul K05 — Schimbare în zbor*