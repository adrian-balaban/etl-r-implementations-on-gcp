# K05. Schimbare în zbor: transform nou

## Scenariu (5 min)
"Compliance urgent" cere `mask_emails`. Un agent modifică teste ca build-ul să treacă. Branch sau toggle vs. config (mapping-YAML)?

## Material sursă
- `TRANSFORMS` în `mapping.py`, `schemas/*.json`, `make spec-test` (comparatorul e dovedit că pică pe orice deteriorare)
- Fixture pinned (`ACCOUNT.src`, `manifest.json`)
- `make verify-project2` (schimbă proiectul fără să atingă codul)
- `CLAUDE.md` ("Nu comite nimic fără a fi cerut primul")
- Diagrame: `docs/diagrams/08-stage-mapping.svg`, `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Branch vs. toggle vs. config** (mapping-YAML e deja config — când e nevoie de cod?): decide pentru cele 3 implementări.
2. **Classifică contractele:** mapping.yaml strict sau permisiv, JSON Schema strict; probează protocolul doar cu argument.
3. **Numărul maxim de versiuni** pentru topic-ul `target-system-target`.
4. **ADL anti-tautologie:** `FOREACH TEST t … ASSERT(t contains at least one assertion)` + identifică "rețeta" agentului.

## Livrabil ADL (punct de start)

```
DESCRIPTION Define in-flight change strategies
CATEGORY Engineering Practices
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE CONST MAX_ENDPOINTS = 10
     DEFINE SERVICE flink AS implementations/flink
     DEFINE SERVICE flink-jdbc AS implementations/flink-jdbc
     DEFINE SERVICE beam-dataflow AS implementations/beam-dataflow
     DEFINE MAPPING ProjectMapping AS spec/contracts/mappings/
     DEFINE SCHEMA TargetSchema AS schemas/target-account.schema.json
ASSERT(mapping.yaml strict where protocol required, permissive otherwise)
ASSERT(JSON Schema validates target document structure)
ASSERT(MAX_ENDPOINTS enforcement: no endpoint supports more than MAX_ENDPOINTS)
ASSERT(FOREACH TEST t IN all tests DO ASSERT(t contains at least one assertion) END)
```