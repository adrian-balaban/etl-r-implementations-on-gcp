# Răspunsuri K10: Cuante arhitecturale

## Task 1: Enumeră punctele de cuplaj static pe implementare

| Punct de cuplaj | Flink (GKE+Kafka+Cloud SQL) | Flink-jdbc (2xCloud SQL + Kafka) | Beam Dataflow (Composer+BigQuery+Kafka) |
|---|---|---|---|
| **VPC / subnet** | 1 VPC, 3 subneturi (bootstrap, GKE, Kafka) | 2 VPC (source + lane) + shared Kafka subnet | 1 VPC landing bucket + Kafka subnet |
| **GKE Cluster** | 1 cluster 4-vCPU, 3 joburi în paralel | 1 cluster 4-vCPU (shared sau dedicat) | N/A (Dataflow managed, fără pod-uri GKE) |
| **Cloud SQL (Postgres)** | 1 instance pipeline + 1 source | 2 instances (source + pipeline) | BigQuery (niciun SQL direct) |
| **Managed Kafka** | 1 cluster, 3 vCPU / 3 GiB | 1 cluster, 3 vCPU / 3 GiB | 1 cluster, shared cu Target System mock |
| **Cloud Run (mock)** | 1 revizie, 1 vCPU / 512 MiB | 1 revizie, 1 vCPU / 512 MiB | 1 revizie, 1 vCPU / 2 GiB |
| **Composer / Dataform** | Niciunul | Niciunul | Composer 2 Small + Dataform models |
| **Terraform state** | 1 bucket, count expressions | 1 bucket + 4 state rm extra la destroy | 1 bucket landing, state separat per project |

**Total cuante static per implementare:**
- **Flink:** ~7 puncte de cuplaj (VPC, GKE, 2 SQL, Kafka, Cloud Run, Terraform)
- **Flink-jdbc:** ~9 puncte (VPC x2, GKE, 2 SQL x2, Kafka, Cloud Run x2, Terraform extra)
- **Beam Dataflow:** ~5 puncte (VPC, Kafka shared, Cloud Run, Composer, Dataform)

**Frontiera față de Target System:** Topic-urile Kafka (`target-system-target`, `target-system-confirmations`, `target-system-rejections`) sunt contractul de interacțiune cu sistemul extern, nu fac parte din cuantele interne ale ETL-R.

## Task 2: Aplică Domain Broker

**Domain Broker pattern:** un cluster Kafka per cuantă, deținut de cuanta respectivă.

| Benefit | Cost |
|---|---|
| **Flink:** Izolare totală – fiecare cuantă Flink are Kafka propriu. Nu mai există picări din mock-ul partajat. | **Ramp-up:** +20 min la start (trebuie create 3 clustere Kafka). |
| **Flink-jdbc:** Fiecare cuantă are source pipeline propriu. | **Cost:** +$0.02 pe run (o marjă mică la SKU-uri). |
| **Beam Dataflow:** Poate partaja Kafka (ca și acum), sau puteți crea 3 clustere Kafka separate. | **Complexitate:** Configurarea multi-cluster Kafka nu e trivială pe Dataflow. |

**Scenariu:** Dacă login-ul mock-ului Target System pică în timpul unui run Flink, doar cuanta afectată pune run-ul jos. Cu Domain Broker, restul de 2 cuante Flink continuă să ruleze normal.

## Task 3: ADL static-coupling

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
              WITHIN Beam Quantum, shared with Target System
ASSERT(Do not allow static coupling between quanta not specified here)
```

**Sfat:** `ASSERT(Do not allow static coupling between quanta not specified here)` oprește agenții de la a genera `Kafka Broker written by Administration Quantum` dacă nu e declarat explicit.

## Task 4: `ASSERT(Do not allow static coupling between quanta not specified here)`

**Cum funcționează:**
- Fitness function-ul rulează la fiecare pull request, verificând declarațiile `DEFINE QUANTUM` din ADL.
- Dacă un agent generează o legătură ca `Kafka Broker WITHIN Administration Quantum`, aserțiunea va pică, pentru că `Administration Quantum` nu are Kafka Broker declarat în ADL.
- Agentul trebuie să adauge `DEFINE BROKER Administration AS ... WITHIN Administration Quantum` sau să justifice de ce nu e nevoie de broker.

**Rezultat:** Fiecare cuantă are broker-ul său explicit declarat, prevenind cuplajul accidental între cuante.