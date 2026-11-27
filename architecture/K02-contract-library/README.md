# K02. The contract as a library shared between languages

## Scenario (5 min)
The team wants the three implementations (Flink, Flink-jdbc, Beam Dataflow) not to drift in the `.FLG/.CHS/.DAT` names and the shared columns. Per `CLAUDE.md`, `verify-project2` runs another project without touching code, by editing only files under `spec/contracts/`.

## Source material
- `spec/contracts/artefacts.json` (the "contract between languages": Python at runtime, Java from the classpath)
- The `contracts/` symlinks in each implementation
- The `ACCOUNT.src` + `manifest.json` fixtures
- `harness/generate.py` (generates the inputs and tests)
- `make spec-test` (the row-by-row comparator against the oracle)
- `verify-project2` (opens the "project2" project without touching code)
- Diagrams: `docs/diagrams/08-stage-mapping.svg`, `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **An ADL with `DEFINE LIBRARY Contracts AS spec/contracts`** + access assertions (who is allowed in, what is forbidden: `Do not hand-edit spec/fixtures/ACCOUNT.src`).
2. **Design a fitness function** that proves the artefacts' byte-identity (`artefacts.json` vs. the copy inside the jar) — interpolated and tested on one passing case + one failing case.
3. **The "IS DEPENDENT ON" trap** (an existential claim, not per-class) — what an agent with ArchUnit/pytest-arch would generate wrongly.
4. **Where it runs**: CI, on every contract change, or MCP on-demand?

## ADL deliverable (starting point)

```
DESCRIPTION Define contract library shared between implementations
CATEGORY Engineering Practices
DEFINE LIBRARY Contracts AS spec/contracts
     DEFINE CONST ARTEFACT_DAT ".DAT"
     DEFINE CONST ARTEFACT_CHS ".CHS"
     DEFINE CONST ARTEFACT_DAT_NAME "{record}.{sequence}.DAT"
     DEFINE CONST ARTEFACT_CHS_NAME "{record}.CHS"
     DEFINE CONST RUN_ID "_run_id"
     DEFINE CONST ACCOUNT_KEY "_account_key"
     DEFINE CONST CONTENT_NAME "raw:" + sha256(raw line)[:16]
     DEFINE CONST SOURCE_KEY ""
     ASSERT(artefact names derive from Contracts)
     ASSERT(byte-identic.jar copy of artefacts.json)
```