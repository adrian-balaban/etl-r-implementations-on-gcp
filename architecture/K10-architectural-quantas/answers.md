# K10 answers: Architectural quanta

## Task 1: Enumerate the static coupling points per implementation

| Coupling point | Flink (GKE+Kafka+Cloud SQL) | Flink-jdbc (2×Cloud SQL + Kafka) | Beam Dataflow (Composer+BigQuery+Kafka) |
|---|---|---|---|
| **VPC / subnet** | 1 VPC, 3 subnets (bootstrap, GKE, Kafka) | 2 VPCs (source + lane) + a shared Kafka subnet | 1 VPC landing bucket + a Kafka subnet |
| **GKE cluster** | 1 cluster 4-vCPU, 3 jobs in parallel | 1 cluster 4-vCPU (shared or dedicated) | N/A (managed Dataflow, no GKE pods) |
| **Cloud SQL (Postgres)** | 1 pipeline instance + 1 source | 2 instances (source + pipeline) | BigQuery (no direct SQL) |
| **Managed Kafka** | 1 cluster, 3 vCPU / 3 GiB | 1 cluster, 3 vCPU / 3 GiB | 1 cluster, shared with the Target System mock |
| **Cloud Run (mock)** | 1 revision, 1 vCPU / 512 MiB | 1 revision, 1 vCPU / 512 MiB | 1 revision, 1 vCPU / 2 GiB |
| **Composer / Dataform** | none | none | Composer 2 Small + Dataform models |
| **Terraform state** | 1 bucket, count expressions | 1 bucket + 4 extra state rm at destroy | 1 landing bucket, state split per project |

**Total static quanta per implementation:**
- **Flink:** ~7 coupling points (VPC, GKE, 2× SQL, Kafka, Cloud Run, Terraform)
- **Flink-jdbc:** ~9 points (2× VPC, GKE, 2× 2× SQL, Kafka, 2× Cloud Run, extra Terraform)
- **Beam Dataflow:** ~5 points (VPC, shared Kafka, Cloud Run, Composer, Dataform)

**The boundary against the Target System:** the Kafka topics (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) are the interaction contract with the external system, not part of ETL-R's internal quanta.

## Task 2: Apply the Domain Broker

**The Domain Broker pattern:** one Kafka cluster per quantum, owned by that quantum.

| Benefit | Cost |
|---|---|
| **Flink:** total isolation — every Flink quantum gets its own Kafka. No more falls from the shared mock. | **Ramp-up:** +20 min at start (3 Kafka clusters must be created). |
| **Flink-jdbc:** every quantum owns its source pipeline. | **Cost:** +$0.02 per run (a small margin on the SKUs). |
| **Beam Dataflow:** can keep sharing Kafka (as today), or create 3 separate clusters. | **Complexity:** multi-cluster Kafka setup is not trivial on Dataflow. |

**The scenario:** if the Target System mock's Kafka login dies during a Flink run, only the affected quantum takes the run down. With the Domain Broker, the other 2 Flink quanta keep running normally.

## Task 3: The static-coupling ADL

```
DESCRIPTION Define architectural quanta for ETL-R
CATEGORY Static Coupling
DEFINE SYSTEM ETL-R AS etl-r-implementations-on-gcp
     DEFINE QUANTUM Flink Quantum
          DEFINE DATABASE account_source_db AS account_source
          DEFINE DATABASE account_target_db AS account_target
          DEFINE BROKER Kafka Broker AS kafka-broker
              WITHIN Flink Quantum
     DEFINE QUANTUM FlinkJdbc Quantum
          DEFINE DATABASE account_source_db AS account_source
          DEFINE DATABASE etlr_source AS source tables
          DEFINE BROKER Kafka Broker AS kafka-broker
              WITHIN FlinkJdbc Quantum
     DEFINE QUANTUM Beam Quantum
          DEFINE DATABASE account_target_db AS account_target
               WITHIN BigQuery datasets
          DEFINE BROKER Kafka Broker AS kafka-broker
               WITHIN Beam Quantum, shared with the Target System
ASSERT(Do not allow static coupling between quanta not specified here)
```

**The hint:** `ASSERT(Do not allow static coupling between quanta not specified here)` stops an agent from generating `Kafka Broker written by Administration Quantum` unless it is declared explicitly.

## Task 4: `ASSERT(Do not allow static coupling between quanta not specified here)`

**How it works:**
- The fitness function runs on every pull request, checking the `DEFINE QUANTUM` declarations in the ADL.
- If an agent generates a link like `Kafka Broker WITHIN Administration Quantum`, the assertion fails — `Administration Quantum` has no Kafka Broker declared in the ADL.
- The agent must add `DEFINE BROKER Administration AS ... WITHIN Administration Quantum`, or justify why no broker is needed.

**The result:** every quantum owns its explicitly declared broker, preventing accidental coupling between quanta.