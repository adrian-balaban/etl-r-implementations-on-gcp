# K05 answers: Change in flight

## Task 1: Branch vs. toggle vs. config

| Strategy | When it is the right choice | For the 3 implementations |
|---|---|---|
| **Branch** | feature development that needs separate tests, where failures must not hit main | each implementation keeps its own `Makefile` + `make conformance`; separate branches are acceptable for parallel testing |
| **Toggle** | changes runtime behaviour without a new deploy; fits opt-in/opt-out features | not ETL-R's case (mapping changes are deploy-time, not runtime) |
| **Config (mapping-YAML)** | changes the transform without changing the code; the mapping is already a config file | **all implementations** read `mappings/*.yaml` from `spec/contracts/` at runtime (Python) or build time (Java). Changing the mapping is changing configuration, not code. |

**Conclusion:** for `mask_emails`, or any new field, **use config (mapping-YAML)**. No implementation needs code changes — improve the centralized contract `artefacts.json` or the YAML mapping.

## Task 2: Classify the contracts

| Contract | Strict or permissive | The argument |
|---|---|---|
| **mapping.yaml** | **Permissive** for enum values (A↔C↔D), **strict** for protocol-backed types | `STATUS=X` is rejected as `MAP_UNMAPPED_ENUM_VALUE` — a failed mapping, never a guess. The protocol (gRPC/REST) is strict only when interoperability requires it. |
| **JSON Schema** | **Strict** — validates the target document's structure | the `target schema` defines the required columns (`accountId`, `STATUS`, `BALANCE`) and the types. Anything missing ⇒ `SCHEMA_INVALID`. |
| **Protocol (gRPC/REST)** | **Strict only when needed** | `USING gRPC` in a contract means the teams must use gRPC. Without an argument, the linter does not enforce the protocol. |
| **Artefact names (.DAT/.CHS)** | **Strict** — standardized names from `artefacts.json` | any deviation ⇒ `make spec-test` fails on `PARSE_MISSING_FIELD` or `SCHEMA_INVALID`. |

## Task 3: The maximum number of versions

The Kafka topic `target-system-target` has one major version, imposed by the JSON schema. Each implementation supports:
- **Flink (SQL)**: one active version; a schema change fails conformance unless the `target schema` is updated
- **Flink-jdbc**: one version; a strict schema, because the database enforces the types
- **Beam Dataflow**: one version, Composer-managed — changing the topic requires a DAG restart

**The limit:** there is no hard cap on the number of versions, but the `MAX_ENDPOINTS = 10` ADL assertion prevents version sprawl.

## Task 4: The anti-tautology ADL

```
ASSERT(FOREACH TEST t IN all tests DO ASSERT(t contains at least one assertion) END)
```

**The agent's recipe:** an agent could generate `ASSERT(true)` as an "assertion" — a tautology. The ADL counters it by:
- checking that the assertion contains a real comparison or a predicate
- `FOREACH ASSERT a IN test DO ASSERT(a is not tautology) END` (pseudocode)
- running the fitness function on every new test: if the build fails for "no assertions", the agent added an empty test

**Prevention:** the `make-the-grade-pages-*/answers.md` material has dedicated tasks for spotting and countering the agent's recipes.

---
*Generated in the K05 quadrant — Change in flight*