# Diagrams

Shared, implementation-neutral diagrams at the top; one implementation's detail in `beam-dataflow/`, `flink/` or
`flink-jdbc/`.

Render: `PLANTUML_JAR=~/bin/plantuml.jar ./render.sh` — PlantUML **1.2026.8**, `!pragma layout smetana`
(no Graphviz), C4 from the stdlib. Commit `.puml` and `.svg` together; other PlantUML versions rewrite
the whole SVG.

## Shared

| Diagram | Shows |
|---|---|
| [00-overview](00-overview.svg) | the whole idea: lane, doors, equation, one spec → many implementations |
| [02-pipeline-flow](02-pipeline-flow.svg) | canonical stages and their outputs |
| [03-two-doors](03-two-doors.svg) | one record through `parse → map → schema` |
| [04-c4-context](04-c4-context.svg) | C1: actors, ETL-R, external systems |
| [08-stage-mapping](08-stage-mapping.svg) | canonical stage → each implementation's artefact |
| [09-data-model](09-data-model.svg) | the seven tables, logical |
| [10-conformance-sequence](10-conformance-sequence.svg) | `make conformance` |
| [11-deployment](11-deployment.svg) | local and GCP, per implementation |
| [14-extend-not-rewrite](14-extend-not-rewrite.svg) | a new project edits only `spec/contracts/` |

## Per implementation

| | beam-dataflow | flink | flink-jdbc |
|---|---|---|---|
| Containers (C2) | [c2](beam-dataflow/c2-containers.svg) | [c2](flink/c2-containers.svg) | [c2](flink-jdbc/c2-containers.svg) |
| Components (C3) | [pipelines](beam-dataflow/c3-pipelines.svg) | [variants](flink/c3-variants.svg) | [gate handshake](flink-jdbc/gate-handshake.svg) |
| One run | [DAG run](beam-dataflow/sequence-dag-run.svg) · [record journey](beam-dataflow/activity-record-journey.svg) | [run](flink/sequence-run.svg) | [run](flink-jdbc/sequence-run.svg) |
| Physical data | [BigQuery](beam-dataflow/erd-bigquery.svg) | [Postgres](flink/erd-postgres.svg) | flink's, plus `etlr_source` ([spec](../../spec/PIPELINE.md#source-system-as-a-database)) |
| GCP | [deployment](beam-dataflow/deployment-gcp.svg) | [deployment](flink/deployment-gcp.svg) | [deployment](flink-jdbc/deployment-gcp.svg) |

Identifiers that predate the renames (`mig_vpc`, `recon-service.jar`, `ro.mig`) are
kept where the code and Terraform still use them.
