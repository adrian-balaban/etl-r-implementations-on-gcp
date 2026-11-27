# K02. Contractul ca bibliotecă partajată între limbaje

## Scenariu (5 min)
Echipa vrea ca cele trei implementări (Flink, Flink-jdbc, Beam Dataflow) să nu deriveze în denumirile `.FLG/.CHS/.DAT` și ale coloanelor partajate. Conform `CLAUDE.md`, `verify-project2` rulează un alt proiect fără să atingă codul, doar editând fișierele din `spec/contracts/`.

## Material sursă
- `spec/contracts/artefacts.json` ("contractul dintre limbaje": Python la runtime, Java din classpath)
- Symlink-urile `contracts/` în fiecare implementare
- Fixture-urile `ACCOUNT.src` + `manifest.json`
- `harness/generate.py` (generează inputurile și teste)
- `make spec-test` (comparatorul rând-cu-rând contra oracolului)
- `verify-project2` (deschide proiectul "project2" fără să atingă codul)
- Diagrame: `docs/diagrams/08-stage-mapping.svg`, `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **ADL cu `DEFINE LIBRARY Contracts AS spec/contracts`** + aserțiuni de acces (cine are voie, ce e interzis: `Do not hand-edit spec/fixtures/ACCOUNT.src`).
2. **Creează un plan de fitness function** care dovedește byte-identitatea artefactelor (`artefacts.json` vs. copiile din jar) — interpolat și testat pe un caz care trece + unul care pică.
3. **Capcana "IS DEPENDENT ON"** (claim existențial, nu per-clasă) — ce ar genera greșit un agent cu ArchUnit/pytest-arch.
4. **Unde rulează**: CI, la schimbarea contractului sau MCP on-demand?

## Livrabil ADL (punct de start)

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