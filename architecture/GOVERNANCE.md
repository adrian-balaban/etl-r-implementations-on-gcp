# Governance register — the ETL-R katas and their ADL

**Owner:** the masterclass participant · **Baseline:** `main` @ `bfe7563` (2026-11-27)
**Structure model:** `total-recall/plugins/total-recall/architecture` (adl/ + diagrams/ + GOVERNANCE.md + REVIEW.md).

## 1. What lives here

The K01–K18 katas exercise the Architecture as Code checklist on the repo's real material.
The ADL scattered across the `answers.md` files is consolidated in `adl/`: one file per
family, with a full header and tagged assertions. Exercise pseudo-syntax, not a real parser.
Where the course's worked example leaks into an exercise (Test Session, students, sample
teams), the consolidated `adl/` restates it on this repo's real structure — stages, Kafka
topics, tables, files, the one real author; the kata folders keep the original exercise.

## 2. The tag scheme

- `# [Kxx-n]` — the source kata, and the assertion's order within it (e.g. `[K03-4]` is K03's fourth assertion).
- Assertions repeated by several katas appear once, carrying combined tags (e.g. `[K08-4 K10-1]`).
- The reverse link: §3 says what each family protects and how it is verified today.

## 3. The fitness-function register

| ADL file | Katas | What it protects | Source material | Verified today via | Remediation on failure |
|---|---|---|---|---|---|
| `structure.adl` | K01, K03 | the boundaries between implementations; every class's home | `CLAUDE.md`, `spec/contracts/README.md` | `adl-test` (pinned boundaries); `verify-project2`; `git diff --quiet` | fix the code, or the ADL and its test together |
| `contracts.adl` | K02 | the contract between languages, byte-identical | `spec/contracts/artefacts.json` | `make spec-test` + `adl-test` ([K02-1] derivation) | fix the contract or the code; never the fixture |
| `code-quality.adl` | K04 | complexity under threshold; main sequence | `spec/conformance/compare.py` | `make spec-test` | refactor onto the main sequence |
| `architecture-style.adl` | K05, K15 | new transforms through config; the S1–S6 residues | `spec/contracts/mappings/`, `schemas/` | `make verify-project2`; `make conformance-all` | a new project touches only `spec/contracts/` |
| `data-governance.adl` | K07, K08 | database access; a DLQ with a human path | `spec/PIPELINE.md`, `docs/evidence/` | conformance; the MCP `get_balance(run_id)` | declare the access or change the topology |
| `static-coupling.adl` | K10 | every broker owned by its quantum | `terraform/` per implementation | static Terraform/IAM analysis | declare the quantum or drop the link |
| `system.adl` | K11 | every sync/async edge declared | `spec/PIPELINE.md`, `docs/evidence/` | the Kafka flow (dynamic) | declare the edge or remove the call |
| `workflows.adl` | K12 | the orchestrated run; eventual consistency | `docs/diagrams/03-two-doors.svg` | `make conformance-all` (balance + settle) | nothing lost silently; a DLQ with a reason |
| `capabilities.adl` | K09, K14 | cost and capabilities, on trend | `docs/evidence/`, `cost-estimate.md` | the MCP `get_cost_last_run(run_id)`, monthly | under the threshold, or an exemption with owner and expiry |
| `governance.adl` | K06, K16, K17, K18 | load-bearing walls; agents, teams, SBOM | `CLAUDE.md`, `git log` | CI gates; a monthly audit | the dispositions in §4 and §5 |

## 4. Dispositions for a failed assertion (K06)

1. **Fix** — the implementation drifted; the architecture was right.
2. **Revise** — the rule was wrong: the ADL and its test change in the same commit.
3. **Exemption** — an explicit owner and expiry; once expired, a Fail at the next pass.

## 5. Agents (K17)

Agents are contributors, scoped to one quantum. Deterministic checks — conformance, the
balance, `settle` — are the only gate; an LLM judge is not proof. The human path stays at
`reject_log`, with closed reasons and `record_lineage` as the trace.

## 6. Proposed MCP tools (K08, K16, K17)

Proposed by the katas; not implemented tools yet.

- `get_balance(run_id)` — after every window; an alert, not a gate.
- `get_conformance()` — on every build/PR; the only gate.
- `get_cost_last_run(run_id)` — monthly, against the thresholds in `capabilities.adl`.

## 7. What runs from CI today

Two workflows: `spec-test.yml` → `make spec-test` (the comparator, the load harness, the
derived fixtures) and `adl-test.yml` → `make adl-test` — three pure-Python harnesses in
`architecture/tests/` (no GCP, no podman):

| Harness (`adl-test`) | What it underwrites | What it does not cover |
|---|---|---|
| `test_adl_lint.py` | every family: full header, ≥1 ASSERT, [Kxx-n] tags ↔ the K01–K18 folders; GOVERNANCE and README list everything | semantics beyond form |
| `test_contracts.py` | `contracts.adl` [K02-1]: the constants equal `artefacts.json`; runtime derivation through `artefacts.py`; the shapes in the oracle | the Java jar copy — in the lanes' suites, locally |
| `test_boundaries.py` | `structure.adl` [K03-1..3]: cross-lane paths and imports pinned; symlinks only to spec/ | Maven GAV, Markdown, relation semantics |

**The boundaries finding**, discovered while building the harness: flink-jdbc reuses
flink's Maven modules in its reactor, `mig.common`, Terraform modules and test fixtures.
K03 discusses exactly this share-vs-duplicate trade-off; the harness keeps that sharing
pinned, not hidden.

Every `.adl` file now has at least one CI harness. The remaining assertions stay exercise
pseudo-ADL: `make conformance-all` is local, the MCP tools are proposed — building them is
the katas' subject, not the repo's state.