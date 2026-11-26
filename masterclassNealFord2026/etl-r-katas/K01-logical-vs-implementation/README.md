# K01. Arhitectură logică vs. implementare

## Scenariu (5 min)
Un agent a generat structura de directoare a implementării `flink` din repo-ul ETL-R. Toate funcțiile rulează: `make conformance` returnează `68 / 68, staged`. Arhitectura logică coincide cu structura codului, dar e suficiente?

## Material sursă
- `implementations/flink/` (`gate/`, `recon/`, `sql/`, `variant-flink-sql-batch-job/`, `variant-flink-datastream-batch-job/`, `target-system-mock/`)
- `spec/PIPELINE.md` (8 stațiuni fixe, ecuația de balanță `src_read = written + rejected`)
- `CLAUDE.md` ("Nu comite nimic fără a fi cerut primul")
- Diagrame: `docs/diagrams/flink/c2-containers.svg`, `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Citește directoarele ca arhitectură** — care domainuri, subdomainuri și component(e) descrie structura repo-ului?
   - Identifică eventualele clasele orfane (Entity Trap: `sql/` numit după tabele, nu după comportament).
2. **Scrie ADL-ul de domainuri** pentru întregul repo (`spec/`, `implementations/<x>`, `harness`, `conformance`) + structura de directoare pe care o presupune.
   - `ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)` — ce prinde și ce rată.
3. **Cum ar simpla viața unui agent generând o implementare #4** care are acest ADL drept context?
   - Ce lipsă de cod ar avea nevoie?

## Livrabil ADL (pore de start)

```
DESCRIPTION Define ETL-R domains
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DOMAIN Spec AS spec
     DEFINE SERVICE flink AS implementations/flink
     DEFINE SERVICE flink-jdbc AS implementations/flink-jdbc
     DEFINE SERVICE beam-dataflow AS implementations/beam-dataflow
ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)
```

## Răspuns la task-uri (discuție echipă, 15 min)

- **Task 1**: Repo-ul are 3 Service-uri top-level (flink, flink-jdbc, beam-dataflow) sub director `implementations/`. Există directoare specifice fiecarei implementări: `gate/`, `recon/`, `sql/`, `target-system-mock/` etc. Domainii logici sunt: Spec (fișierele specifice repo-ului), Harness (fișierele de generație a testelor), Conformance (oracol + comparație). Clasele orfane includ orice fișier Java/Python plasat la rădăcină a `implementations/...` sau `spec/`, nu într-o componentă definită explicit.

- **Task 2**: ADL template complet pentru repo:
```
DESCRIPTION Define ETL-R domains and services
CATEGORY Structural
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE DOMAIN Spec AS spec
          DEFINE COMPONENT PipelineSpec AS pipelines
          DEFINE COMPONENT Contracts AS contracts
     DEFINE DOMAIN Flink AS implementations/flink
          DEFINE COMPONENT FlinkGate AS gate
          DEFINE COMPONENT FlinkRecon AS recon
          DEFINE COMPONENT FlinkSql AS sql
          DEFINE COMPONENT FlinkTargetMock AS target-system-mock
     DEFINE DOMAIN FlinkJdbc AS implementations/flink-jdbc
          DEFINE COMPONENT JdbcGate AS gate-jdbc
          DEFINE COMPONENT JdbcSource AS intake-jdbc
          DEFINE COMPONENT JdbcTarget AS target-system
     DEFINE DOMAIN BeamDataflow AS implementations/beam-dataflow
          DEFINE COMPONENT BeamExtract AS dataflow_entrypoint.py
          DEFINE COMPONENT BeamTransform AS pipelines/
          DEFINE COMPONENT BeamLoad AS work/
     DEFINE DOMAIN Harness AS harness
          DEFINE COMPONENT Generate AS generate.py
          DEFINE COMPONENT Bundle AS bundle.py
     DEFINE DOMAIN Conformance AS spec/conformance
          DEFINE COMPONENT Compare AS compare.py
          DEFINE COMPONENT Oracle AS fixtures/
ASSERT(CLASSES are only CONTAINED within COMPONENTS within SERVICES)
```

- **Task 3**: Agentul ar avea nevoie doar de `spec/`, `harness/` și `contracts/` pentru a reface structura. Codul real al fiecărei implementări (`variant-flink-sql-batch-job/`, `variant-flink-datastream-batch-job/`) ar rămâne izolat, nu ar fi necesar în ADL ci doar ca *implementation* detaliată.

---
*Generat în cadranul K01 — Arhitectură logică vs. implementare*