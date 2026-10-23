# Cost is an estimate, not an invoice

Every figure here is **quantity × list price**. No free tier, no discount, no committed use.
Quantities come from the timed billing windows. Prices come from the Cloud Billing Catalog API
(europe-west1, USD, fetched 2026-10-22). Each line shows its SKU id, so each price can be looked up.

The real invoice may differ. Billing data lags by a day or more, and was not available when this was written.

## Totals

| Implementation | Billing window | Estimate |
|---|---|---|
| `flink` | 59 min 31 s | **$0.90** |
| `flink-jdbc` | 56 min 45 s | **$0.94** |
| `beam-dataflow` | 2 h 31 min | **$2.33** |

The whole session, three implementations, stayed far below the $30 cap.

## Assumptions

- **Managed Kafka storage: 300 GiB.** The API does not expose it. It is $0.07 to $0.18 in each estimate.
- **Composer database: 20 GiB.** Also not exposed.
- **Cloud NAT data: 2 GiB** of image pulls per run.
- **Cloud Run mock:** billed for the whole window (always on, instance-based).
- **Composer:** billed from environment creation to deletion, not for the whole window.
- **Dataflow:** worker time summed over the six batch jobs of the fixture run and the 200k run. One worker per job.
- **Compute:** 1 DCU is counted as 1 vCPU for Managed Kafka, as Google defines it.
- **Beam with three Dataflow workers** instead of one would cost $2.53.

## What drives the difference

- **Beam pays for Composer**: $1.03 of $2.33. The environment is billed whether or not a DAG runs.
- **Beam also bills for longer**: 2 h 31 min against about 1 h. Managed Kafka and the mock are billed for all of it.
- **The Flink lanes pay for GKE**: about $0.25, and one or two Cloud SQL instances: $0.07 to $0.13.
- **Managed Kafka is the largest line in the Flink lanes**: about $0.36 of $0.90.

## Line items

### flink: $0.90

| Item | Quantity | Unit price | Cost | SKU |
|---|---|---|---|---|
| GKE regional cluster management fee | 0.992 cluster-h | $0.100000 | $0.10 | B561-BFBD-1264 |
| GKE node e2-standard-4: 4 vCPU x hours | 3.968 vCPU-h | $0.023993 | $0.10 | 9FE0-8F60-A9F0 |
| GKE node e2-standard-4: 16 GiB x hours | 15.871 GiB-h | $0.003216 | $0.05 | F268-6CE7-AC16 |
| GKE node boot disk 100 GiB (balanced PD) | 100 GiB | $0.100000 | $0.01 | 6AE1-525F-8B80 |
| Cloud SQL Postgres x1: 1 vCPU each | 0.992 vCPU-h | $0.041300 | $0.04 | 2154-E036-4CB2 |
| Cloud SQL Postgres x1: 3.75 GiB RAM each | 3.72 GiB-h | $0.007000 | $0.03 | 8A88-5E4B-2265 |
| Cloud SQL storage 1 x 10 GiB (assumed minimum) | 10 GiB | $0.170000 | $0.00 | B14E-2B60-F10B |
| Managed Kafka: 3 vCPU = 3 DCU | 2.976 DCU-h | $0.099000 | $0.29 | A916-DD47-F457 |
| Managed Kafka local storage (ASSUMED 300 GiB) | 300 GiB | $0.170000 | $0.07 | D599-616B-2F83 |
| Cloud NAT gateway uptime | 0.992 gateway-h | $0.001400 | $0.00 | 32E2-4EFC-EF9F |
| Cloud NAT IP usage | 0.992 IP-h | $0.005000 | $0.01 | 8515-9425-D2CE |
| Cloud NAT data processed (image pulls, ~2 GiB assumed) | 2 GiB | $0.045000 | $0.09 | 015F-5732-FFF0 |
| VPC Access connector: 2 x e2-micro (min instances) | 1.984 instance-h | $0.008600 | $0.02 | 5619-DD14-5F41 |
| Cloud Run mock, always on: 1 vCPU (instance-based) | 3571 vCPU-s | $0.000018 | $0.06 | 4C19-DF1E-81AB |
| Cloud Run mock, always on: 0.5 GiB | 1785.5 GiB-s | $0.000002 | $0.00 | A1D5-F449-FAFC |
| Cloud Run jobs: orchestrator/seeder CPU (1 vCPU) | 1300 vCPU-s | $0.000018 | $0.02 | 72BD-0AE2-8A8F |
| Cloud Run jobs: orchestrator/seeder memory (1 GiB) | 1300 GiB-s | $0.000002 | $0.00 | 56BC-892D-86F5 |

### flink-jdbc: $0.94

| Item | Quantity | Unit price | Cost | SKU |
|---|---|---|---|---|
| GKE regional cluster management fee | 0.946 cluster-h | $0.100000 | $0.09 | B561-BFBD-1264 |
| GKE node e2-standard-4: 4 vCPU x hours | 3.783 vCPU-h | $0.023993 | $0.09 | 9FE0-8F60-A9F0 |
| GKE node e2-standard-4: 16 GiB x hours | 15.133 GiB-h | $0.003216 | $0.05 | F268-6CE7-AC16 |
| GKE node boot disk 100 GiB (balanced PD) | 100 GiB | $0.100000 | $0.01 | 6AE1-525F-8B80 |
| Cloud SQL Postgres x2: 1 vCPU each | 1.892 vCPU-h | $0.041300 | $0.08 | 2154-E036-4CB2 |
| Cloud SQL Postgres x2: 3.75 GiB RAM each | 7.094 GiB-h | $0.007000 | $0.05 | 8A88-5E4B-2265 |
| Cloud SQL storage 2 x 10 GiB (assumed minimum) | 20 GiB | $0.170000 | $0.00 | B14E-2B60-F10B |
| Managed Kafka: 3 vCPU = 3 DCU | 2.837 DCU-h | $0.099000 | $0.28 | A916-DD47-F457 |
| Managed Kafka local storage (ASSUMED 300 GiB) | 300 GiB | $0.170000 | $0.07 | D599-616B-2F83 |
| Cloud NAT gateway uptime | 0.946 gateway-h | $0.001400 | $0.00 | 32E2-4EFC-EF9F |
| Cloud NAT IP usage | 0.946 IP-h | $0.005000 | $0.00 | 8515-9425-D2CE |
| Cloud NAT data processed (image pulls, ~2 GiB assumed) | 2 GiB | $0.045000 | $0.09 | 015F-5732-FFF0 |
| VPC Access connector: 2 x e2-micro (min instances) | 1.892 instance-h | $0.008600 | $0.02 | 5619-DD14-5F41 |
| Cloud Run mock, always on: 1 vCPU (instance-based) | 3405 vCPU-s | $0.000018 | $0.06 | 4C19-DF1E-81AB |
| Cloud Run mock, always on: 0.5 GiB | 1702.5 GiB-s | $0.000002 | $0.00 | A1D5-F449-FAFC |
| Cloud Run jobs: orchestrator/seeder CPU (1 vCPU) | 1900 vCPU-s | $0.000018 | $0.03 | 72BD-0AE2-8A8F |
| Cloud Run jobs: orchestrator/seeder memory (1 GiB) | 1900 GiB-s | $0.000002 | $0.00 | 56BC-892D-86F5 |

### beam-dataflow: $2.33

| Item | Quantity | Unit price | Cost | SKU |
|---|---|---|---|---|
| Composer 2 Small environment fee | 1.866 env-h | $0.390000 | $0.73 | 4D90-F4D6-E0BA |
| Composer vCPU time (1.5 vCPU: scheduler+web+1 worker) | 2.798 vCPU-h | $0.081000 | $0.23 | 4C39-D447-B489 |
| Composer memory (6 GiB) | 11.193 GiB-h | $0.006000 | $0.07 | 2359-948C-EFCA |
| Composer database storage (ASSUMED 20 GiB) | 20 GiB | $0.190000 | $0.01 | 1A4D-F9EC-BA38 |
| Managed Kafka: 3 vCPU = 3 DCU | 7.552 DCU-h | $0.099000 | $0.75 | A916-DD47-F457 |
| Managed Kafka local storage (ASSUMED 300 GiB) | 300 GiB | $0.170000 | $0.18 | D599-616B-2F83 |
| Cloud NAT gateway uptime | 2.517 gateway-h | $0.001400 | $0.00 | 32E2-4EFC-EF9F |
| Cloud NAT IP usage | 2.517 IP-h | $0.005000 | $0.01 | 8515-9425-D2CE |
| Cloud NAT data processed (image pulls, ~2 GiB assumed) | 2 GiB | $0.045000 | $0.09 | 015F-5732-FFF0 |
| VPC Access connector: 2 x e2-micro (min instances) | 5.034 instance-h | $0.008600 | $0.04 | 5619-DD14-5F41 |
| Cloud Run mock, always on: 1 vCPU (instance-based) | 9062 vCPU-s | $0.000018 | $0.16 | 4C19-DF1E-81AB |
| Cloud Run mock, always on: 2 GiB | 18124 GiB-s | $0.000002 | $0.04 | A1D5-F449-FAFC |
| Dataflow batch vCPU time (6 jobs) | 0.335 vCPU-h | $0.059000 | $0.02 | 161E-6FF0-CFDE |
| Dataflow batch memory time | 1.259 GiB-h | $0.004172 | $0.01 | 3BC3-5EF8-60CF |
| Dataflow persistent disk time (PD standard) | 8.389 GiB-h | $0.000054 | $0.00 | FEB1-430E-1D7F |
| RBAC helper VM e2-standard-2 (~3 min) | 0.1 vCPU-h | $0.023993 | $0.00 | 9FE0-8F60-A9F0 |
