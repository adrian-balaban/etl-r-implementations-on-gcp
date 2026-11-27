# K15 answers: Constant change: stressors and residues

## Task 1: Fill in the stressor matrix

What each stressor strikes:

| Struck by | S1 (a new project) | S2 (GCP price/degraded Kafka) | S3 (20M records) | S4 (Target API v2) | S5 (region/residency) | S6 (a 4th implementation) |
|---|---|---|---|---|---|---|
| **Spec / PIPELINE.md** | ✅ new mappings + conformance revalidation | ⚠️ may need config updates | ✅ may need small adjustments | ⚠️ change the schema + revalidate | ⚠️ may need a multi-region setup | ❌ incompatible with the existing spec |
| **Contracts (artefacts.json, mappings)** | ✅ new mappings | ✅ may need config updates | ✅ stable (the mapping is the volatile part) | ✅ change the JSON Schema contract | ✅ may need region-specific config | ❌ divergence between implementations |
| **Flink (SQL/DataStream)** | ⚠️ needs new tests | ⚡ Kafka variance ±10 min | ✅ scales to 200k | ⚠️ change the target schema | ✅ multi-region possible | ❌ not natively supported |
| **Flink-jdbc** | ✅ pin the mapping + verify-project2 | ⚡ Kafka variance ±10 min | ✅ fast at 200k (pre-run load) | ⚠️ change the source DB | ✅ can migrate to a local Postgres | ❌ not natively supported |
| **Beam Dataflow** | ⚠️ new mappings + generate | ⚡⚡ variance ±20 min (Composer+Kafka) | ✅ can scale, but at 2.5× the cost | ⚠️ change the Kafka contract | ✅ BigQuery multi-region | ⚠️ can be added as a variant |
| **Harness / generate.py** | ✅ new seeded files | ✅ stable | ✅ identical (seeded) | ✅ stable | ✅ stable | ❌ generate.py must be rewritten |
| **Recon / balance** | ✅ equal (55=50+5) | ✅ equal | ✅ equal (200k=200k+5) | ⚠️ the confirmations change | ✅ equal | ❌ a different topology |

**The analyses:**
- **S1** strikes spec and contracts – the residue is `ASSERT(adding a project modifies only spec/contracts/)`.
- **S2** strikes the Kafka variance – the residue is the MCP tool `get_cost_last_run()` + monitoring Kafka's health.
- **S3** strikes scalability – the residue is the cost/runtime limit fitness functions.
- **S4** strikes the schema – the residue is the ADL `ASSERT(JSON Schema validates target document structure)`.
- **S5** strikes the region – the residue is the multi-region configuration in the ADL.
- **S6** strikes the topology – the residue is `ASSERT(Do not allow static coupling between quanta not specified here)`.

## Task 2: Design the residues

**The identified residues:**
1. **Contracts-as-config:** `artefacts.json` + the YAML mappings are the centralized configuration – changes here are safe, not code changes.
2. **A pinned oracle:** `manifest.json` + `ACCOUNT.src` at a fixed SHA-256 – keeps the oracle from drifting.
3. **An abstract stage mapping:** `08-stage-mapping.svg` – the stages are defined at an abstract level, not implementation-specifically.

**The priority:**
- **Business zone:** S1 and S4 are the most critical (changes that hit conformance directly).
- **# stressors covered:** S1, S4, S6 cover 3/6 "essential" stressors.
- **Probability × impact:** S1 (a new project, near-certain), S4 (an API change, likely), S2 (Kafka variance, possible but of high impact).

## Task 3: The ADL for a project change

```
ASSERT(adding a project modifies only spec/contracts/)
ASSERT(manifest.json sha256 pinned: ACCOUNT.src unchanged)
ASSERT(stage mapping abstract: 08-stage-mapping.svg unchanged modulo visual additions)
```

**The hint:** any new change in `implementations/` that is not declared through `spec/contracts/` fails the `ASSERT(adding a project modifies only spec/contracts/)` fitness function — agents cannot route around it easily.

## Task 4: The course's golden rule

**Stressors** that hit business capabilities directly must be mapped to ADL assertions, and the **residues** (contracts-as-config, the pinned oracle, the abstract stage-mapping) are adopted as anticipatory guardians, not as reactions to every change.