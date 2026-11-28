# Governance register — the ETL-R katas and their ADL

**Owner:** the masterclass participant · **Baseline:** `main` @ `7ec2db6` (2026-11-27)
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

One row per assertion. Status: `CI` (a harness in `adl-test`/`spec-test`), `scheduled` (a
history workflow), `local` (podman/conformance), `manual` (a billable GCP run), `exercise`
(declared, not checkable in this repo), `proposed` (a row in `CHECKS-PLAN.md` waiting to land).
Families: `structure.adl` (K01, K03) · `contracts.adl` (K02, K03) · `code-quality.adl` (K04, K14) ·
`architecture-style.adl` (K05, K15) · `data-governance.adl` (K07, K08) · `static-coupling.adl` (K10) ·
`system.adl` (K11) · `workflows.adl` (K12) · `capabilities.adl` (K09, K14) · `governance.adl` (K06, K16, K17, K18).
The lint in `tests/test_adl_lint.py` proves every tag in `adl/` has a row.

| Tag(s) | Owner | Source — where it is checked / what it protects | Trigger | Status |
|---|---|---|---|---|
| K03-1, K03-2, K03-3 | Adrian Balaban | `tests/test_boundaries.py` — lanes reference no sibling | PR | CI |
| K03-4 | Adrian Balaban | `tests/test_structure.py` — the lane graph is acyclic | PR | CI |
| K01-1 | Adrian Balaban | `tests/test_structure.py` — every lane-root entry has a home | PR | CI |
| K03-5, K03-6, K03-7 | Adrian Balaban | `verify-project2` (beam lane) — a new project touches only contracts | local | local |
| K02-1 | Adrian Balaban | `tests/test_contracts.py` — names derive from `artefacts.json` | PR | CI |
| K02-2 | Adrian Balaban | lane Maven suites (copy-artefacts sha) + `tests/test_structure.py` C4 | local | local |
| K02-3, K03-8 | Adrian Balaban | `spec-test`: `test_fixture_is_the_pinned_one` — the pinned `ACCOUNT.src` | PR | CI |
| K02-4 | Adrian Balaban | C14 (`CHECKS-PLAN.md`) — shared churn per month | weekly | scheduled |
| K04-1, K04-2, K04-3 | Adrian Balaban | C9 — `radon`/Lizard complexity under `MAX_CC` (report mode first) | PR | proposed |
| K04-4, K14-5 | Adrian Balaban | C10 — A/I/D distance from the main sequence | PR | proposed |
| K04-5 | Adrian Balaban | `spec-test` comparator suite + `tests/test_damage_cases.py` | PR | CI |
| K05-1 | Adrian Balaban | the mapping loader's strict/permissive parsing, exercised by the lanes | local | local |
| K05-2 | Adrian Balaban | `TargetSchemaValidator` in the beam lane suite | local | local |
| K05-3 | Adrian Balaban | no versioned endpoints exist in this repo | — | exercise |
| K05-4, K05-5 | Adrian Balaban | `tests/test_test_quality.py` — no empty or tautological tests | PR | CI |
| K15-1 | Adrian Balaban | `verify-project2` — a project adds only contracts | local | local |
| K15-2 | Adrian Balaban | `spec-test` — the `ACCOUNT.src` sha256 pin in `manifest.json` | PR | CI |
| K15-3 | Adrian Balaban | `docs/diagrams/08-stage-mapping.svg`, reviewed per project | — | exercise |
| K07-1, K08-1 | Adrian Balaban | C13 — JDBC URLs and IAM bindings against the declared accessors | PR | proposed |
| K07-2, K11-2 | Adrian Balaban | runtime tracing of the channels on a live run | — | exercise |
| K07-3 | Adrian Balaban | `make conformance-all` — settle after the broker drains | local | local |
| K08-4, K10-1 | Adrian Balaban | C12 — every Terraform broker and database claimed by its quantum | PR | proposed |
| K11-1 | Adrian Balaban | runtime tracing — declared edges match observed calls | — | exercise |
| K11-3 | Adrian Balaban | `make conformance-all` — settle confirms every written row | local | local |
| K11-4 | Adrian Balaban | `make conformance-all` — the branches join against the pinned replica | local | local |
| K12-1 | Adrian Balaban | runtime tracing — the saga's calls match the declarations | — | exercise |
| K12-2 | Adrian Balaban | `make conformance-all` drives the real orchestrator | local | local |
| K09-1, K09-2, K09-3, K09-4, K09-5, K09-6 | Adrian Balaban | `docs/evidence/` per GCP run; the MCP `get_cost_last_run(run_id)` | per run | manual |
| K14-1, K14-2, K14-3, K14-4, K14-6 | Adrian Balaban | the capability ADL against the evidence; trend over runs | per run | manual |
| K06-1 | Adrian Balaban | `make conformance-all` — `src_read = written + rejected` | local | local |
| K06-2 | Adrian Balaban | `docs/evidence/` — the mock-outage run lost no queued record | per run | manual |
| K06-3 | Adrian Balaban | `docs/evidence/` — the 200k run on one 4-vCPU GKE node | per run | manual |
| K06-4 | Adrian Balaban | `tests/test_bundle.py` — the `.FLG` outside the archive | PR | CI |
| K16-1, K16-3 | Adrian Balaban | C11 — CycloneDX SBOM at build time | PR | proposed |
| K16-2 | Adrian Balaban | `tests/test_adl_inventory.py` — no technology inventory | PR | CI |
| K17-1, K17-2, K17-3 | Adrian Balaban | agent scope: `CLAUDE.md` + the boundaries; C16/C17 read the trailers | weekly | proposed |
| K18-1 | Adrian Balaban | this register's team ADL; C16 reads the member list | weekly | scheduled |
| K18-2, K18-3, K18-4 | Adrian Balaban | C16 — authors in the team ADL, files inside their domain | weekly | scheduled |
| K18-5 | Adrian Balaban | C17 — agent-trailer merges with conflicts | monthly | scheduled |

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