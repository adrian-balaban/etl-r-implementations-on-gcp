# Răspunsuri K02: Contractul ca bibliotecă partajată

## Task 1: ADL cu `DEFINE LIBRARY Contracts AS spec/contracts`

```
DESCRIPTION Define contract library shared between implementations
CATEGORY Engineering Practices
DEFINE LIBRARY Contracts AS spec/contracts
     DEFINE CONST ARTEFACT_DAT ".DAT"
     DEFINE CONST ARTEFACT_CHS ".CHS"
     DEFINE CONST ARTEFACT_DAT_NAME "{record}.{sequence}.DAT"
     DEFINE CONST ARTEFACT_CHS_NAME "{record}.CHS"
     DEFINE CONST RUN_ID "_run_id"
     DEFINE CONST ACCOUNT_KEY "_account_key"
     DEFINE CONST CONTENT_NAME "raw:" + sha256(raw line)[:16]
     DEFINE CONST SOURCE_KEY ""
     ASSERT(artefact names derive from Contracts)
     ASSERT(byte-identic.jar copy of artefacts.json)
```
Acesta este contractul centralizat pe care toate implementările îl citesc la runtime (Python) sau la build time (Java/Maven). `artefacts.json` conține numele standardizate pentru `.DAT`, `.CHS`, `.FLG`, `.RPT`, `.ERR` și coloanele partajate `_run_id`, `_account_key`. Interdicția `Do not hand-edit spec/fixtures/ACCOUNT.src` devine o aserțiune ADL: `ASSERT(ACCOUNT.src unchanged since sha256 pinned in fixtures/manifest.json)`.
## Task 2: Plan de fitness function

**Caz care trece:**
- Build-ul Java rulează `mvn dependency:copy-artefacts` care copiează `artefacts.json` din `spec/contracts/` în classpath
- Testul verifică checksum-ul SHA-256 al copiei vs. sursa
- `make verify-project2` trece fără erori (zero modificări în `pipelines/` sau `apps/`)

**Caz care pică:**
- Agentul editează `spec/fixtures/ACCOUNT.src` direct → checksum-urile diferă → `make spec-test` pică
- `artefacts.json` modificat fără actualizarea consistentă a mapping-urilor → `make conformance` pică pe eroarea `SCHEMA_INVALID`
- Testul Python citește `artefacts.json` din directorul greșit → nume de fișiere incorecte → `load` step pică cu `PARSE_MISSING_FIELD`

Fitness function interpolată (din ADL): `DEFINE CONST MAX_SHARED_CHANGES = 2` + `ASSERT(shared changes no more than MAX_SHARED_CHANGES times per month)` rulabil pe CI.

## Task 3: Capcana "IS DEPENDENT ON"

**Rezultatul greșit al agentului:**
- Agentul generează o regulă ArchUnit: `ArchRule senderDependsOnModel = classes().that().resideInAPackage("..testing.question_service.sender..").should().dependOnClassesThat().resideInAPackage("..testing.test_model..");`
- Acest lucru pică pentru că **IS DEPENDENT ON** este o **claim existențial**: "at least one class folosește B", nu "every class".
- Dacă o singură clasă helper utilitară folosește test_model, check-ul trece, deși 99% din clase nu au nicio legătură cu modelul.
- În realitate, `Question Sender` depinde de `Test Model` doar prin clasa specifică `Test Loader`, nu prin orice clasă din pachet.

**Corectitudine:** `IS DEPENDENT ON` generatează `at least one class uses`, nu "every class". Pentru o verificare per-clasă, arhitectul ar trebui să ruleze o regulă custom de tip `per class` sau să folosească `HAS NO DEPENDENCY ON` pentru a lista explicit interdependențele.

## Task 4: Unde rulează fitness function-ul

- **CI/CD trigger**: La fiecare build/PR, rulează `make spec-test` și verifică byte-identitatea `artefacts.json`. Dacă pică, build-ul e blocat.
- **MCP on-demand**: Un agent sau arhitectul poate rula unealta MCP `get_artefact_config()` pentru a compara arborele curent `artefacts.json` cu cel canonic stocat în governance context.
- **Interactiv**: În timpul unui kata/workshop, agentul primește ADL-ul ca context și rulează manual verificarea `ASSERT(artefact names derive from Contracts)` împotriva arborelui real al fișierelor generate de build.

**Recomandare:** CI/CD triggered as the release gate, plus MCP tools for on-demand checks (așa cum e recomandat în `make-the-grade-pages-*/answers.md` pentru alte task-uri).