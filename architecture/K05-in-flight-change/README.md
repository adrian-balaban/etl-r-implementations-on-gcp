# K05. Change in flight: a new transform

## Scenario (5 min)
"Urgent compliance" demands `mask_emails`. An agent starts modifying tests to make the build pass. Branch or toggle — or config (mapping-YAML)?

## Source material
- `TRANSFORMS` in `mapping.py`, `schemas/*.json`, `make spec-test` (the comparator is proven to fail on any damage)
- The pinned fixture (`ACCOUNT.src`, `manifest.json`)
- `make verify-project2` (changes the project without touching code)
- `CLAUDE.md` ("Never commit unless asked")
- Diagrams: `docs/diagrams/08-stage-mapping.svg`, `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Branch vs. toggle vs. config** (mapping-YAML is already config — when do you actually need code?): decide for all 3 implementations.
2. **Classify the contracts:** mapping.yaml strict or permissive, JSON Schema strict; argue the protocol only with an argument.
3. **The maximum number of versions** for the `target-system-target` topic.
4. **The anti-tautology ADL:** `FOREACH TEST t … ASSERT(t contains at least one assertion)` + identify the agent's "recipe".

## ADL deliverable (starting point)

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