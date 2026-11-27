# Răspunsuri K01: Arhitectură logică vs. implementare

## Task 1: Citește directoarele ca arhitectură

**Domainuri identificate:**

- `Spec` - conține `pipelines/`, `contracts/`, `fixtures/`, `harness/`
- `Flink` - conține `implementations/flink/` cu subdirectoare: `gate/`, `recon/`, `sql/`, variantele `variant-flink-sql-batch-job/`, `variant-flink-datastream-batch-job/`, `target-system-mock/`
- `FlinkJdbc` - conține `implementations/flink-jdbc/` cu subdirectoare: `gate-jdbc/`, `sql/`, `target-system-mock/`, `intake-jdbc/`
- `BeamDataflow` - conține `implementations/beam-dataflow/` cu subdirectoare: `pipelines/`, `harness/`, `dataflow_entrypoint.py`, `composer/`, `dataform/`

**Clase orfane (Entity Trap):**

- Orice fișier `.java`/`.py` plasat la rădăcină a `implementations/...` sau `spec/` fără un component definită explicit în ADL
- Directoarele `sql/` (în Flink) sau `target-system-mock/` sunt numite după entitate de date, nu după comportament de componentă
- `common/`, `config/` sau `component-tests/` fără proprietate Domeniu clară

## Task 2: ADL-ul de domainuri pentru repo (template simplu)

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
          DEFINE COMPONENT BeamDockerfile Dockerfile.dataflow / Dockerfile.dataform
          DEFINE COMPONENT BeamLocalDevelopment AS local
          DEFINE COMPONENT BeamGcp GCP
          DEFINE COMPONENT BeamContainer AS composer / dataflow
          DEFINE COMPONENT BeamCI AS .github
          DEFINE COMPONENT BeamMaven pom.xml
     DEFINE DOMAIN Harness AS harness
          DEFINE COMPONENT Generate AS generate.py
          DEFINE COMPONENT Bundle AS bundle.py
     DEFINE DOMAIN Conformance AS spec/conformance
          DEFINE COMPONENT Compare AS compare.py
          DEFINE COMPONENT Oracle AS fixtures/expected/
ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)
```

## Task 3: Contextul agentului pentru implementare nouă

Agentul ar avea nevoie doar de:

- `spec/PIPELINE.md` - specificația funcțională (8 stațiuni, ecuatia de balanță, reason-urile enumerate)
- `spec/contracts/` - contractul dintre limbaje (mapping-urile, schema-urile JSON, fixture-urile .def)
- `harness/generate.py` - generatorul de inregistrări seeded (55 inregistrări cu 5 defecte în sarcinile de parse)
- `spec/fixtures/ACCOUNT.src` - fisierul imutabil cu header + 55 de înregistrări (sha256: `86f76332f08e7c8c0a0a78a4366fb124151f3467fbb3a68a556fd6461604426c`)

Codul real al fiecărei implementări rămâne izolat ca _implementation details_, necesar doar la timpul de build/test local (`make generate | run | test | conformance | verify`).
