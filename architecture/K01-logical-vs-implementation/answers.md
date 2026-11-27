# K01 answers: Logical vs. implementation architecture

## Task 1: Read the directories as architecture

**Domains identified:**

- `Spec` — holds `pipelines/`, `contracts/`, `fixtures/`, `harness/`
- `Flink` — `implementations/flink/` with subdirectories: `gate/`, `recon/`, `sql/`, the variants `variant-flink-sql-batch-job/`, `variant-flink-datastream-batch-job/`, `target-system-mock/`
- `FlinkJdbc` — `implementations/flink-jdbc/` with subdirectories: `gate-jdbc/`, `sql/`, `target-system-mock/`, `intake-jdbc/`
- `BeamDataflow` — `implementations/beam-dataflow/` with subdirectories: `pipelines/`, `harness/`, `dataflow_entrypoint.py`, `composer/`, `dataform/`

**Orphan classes (the Entity Trap):**

- Any `.java`/`.py` file placed at the root of `implementations/...` or `spec/`, without a component explicitly defined in the ADL
- The `sql/` (in Flink) or `target-system-mock/` directories are named after a data entity, not after a component's behaviour
- `common/`, `config/` or `component-tests/` without a clear Domain owner

## Task 2: The domains ADL for the repo (simple template)

```
DESCRIPTION Define ETL-R domains and services
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DOMAIN Spec AS spec
          DEFINE COMPONENT PipelineSpec AS pipelines
          DEFINE COMPONENT Contracts AS contracts
          DEFINE COMPONENT Fixtures AS fixtures
          DEFINE COMPONENT Harness AS harness
          DEFINE COMPONENT Conformance AS spec/conformance
     DEFINE DOMAIN Flink AS implementations/flink
          DEFINE COMPONENT FlinkGate AS gate
          DEFINE COMPONENT FlinkRecon AS recon
          DEFINE COMPONENT FlinkSql AS sql
          DEFINE COMPONENT FlinkTargetMock AS target-system-mock
          DEFINE COMPONENT FlinkCommon AS common
          DEFINE COMPONENT FlinkComponentTests AS component-tests
     DEFINE DOMAIN FlinkJdbc AS implementations/flink-jdbc
          DEFINE COMPONENT JdbcGate AS gate-jdbc
          DEFINE COMPONENT JdbcSource AS intake-jdbc
          DEFINE COMPONENT JdbcTarget AS target-system
          DEFINE COMPONENT JdbcCommon AS common
          DEFINE COMPONENT JdbcComponentTests AS component-tests-jdbc
     DEFINE DOMAIN BeamDataflow AS implementations/beam-dataflow
          DEFINE COMPONENT BeamExtract AS dataflow_entrypoint.py
          DEFINE COMPONENT BeamTransform AS pipelines/
          DEFINE COMPONENT BeamLoad AS work/
          DEFINE COMPONENT BeamDataform AS dataform
          DEFINE COMPONENT BeamHarness AS harness
          DEFINE COMPONENT BeamTests AS tests/
          DEFINE COMPONENT BeamMakefile AS Makefile
          DEFINE COMPONENT BeamDockerfile AS Dockerfile.dataflow / Dockerfile.dataform
          DEFINE COMPONENT BeamLocalDevelopment AS local
          DEFINE COMPONENT BeamGcp AS GCP
          DEFINE COMPONENT BeamContainer AS composer / dataflow
          DEFINE COMPONENT BeamCI AS .github
          DEFINE COMPONENT BeamMaven AS pom.xml
     DEFINE DOMAIN Harness AS harness
          DEFINE COMPONENT Generate AS generate.py
          DEFINE COMPONENT Bundle AS bundle.py
     DEFINE DOMAIN Conformance AS spec/conformance
          DEFINE COMPONENT Compare AS compare.py
          DEFINE COMPONENT Oracle AS fixtures/expected/
ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)
```

## Task 3: The agent's context for a new implementation

The agent would need only:

- `spec/PIPELINE.md` — the functional specification (eight stages, the balance equation, the enumerated reasons)
- `spec/contracts/` — the contract between languages (the mappings, the JSON schemas, the `.def` fixtures)
- `harness/generate.py` — the seeded record generator (55 records with 5 defects in the parse tasks)
- `spec/fixtures/ACCOUNT.src` — the immutable file with header + 55 records (sha256: `86f76332f08e7c8c0a0a78a4366fb124151f3467fbb3a68a556fd6461604426c`)

Each implementation's real code stays isolated as _implementation details_, needed only at local build/test time (`make generate | run | test | conformance | verify`).

---
*Generated in the K01 quadrant — Logical vs. implementation architecture*