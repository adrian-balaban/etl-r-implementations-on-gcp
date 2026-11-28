# Review — the Architecture as Code checklist, with verdicts

**Repo:** `etl-r-implementations-on-gcp` · **Baseline:** `main` @ `7ec2db6` (2026-11-27), reviewed
while landing `CHECKS-PLAN.md` · **Owner:** Adrian Balaban
**Checklist:** `ARCHITECTURE_AS_CODE_CHECKLIST.md` from the masterclass material (outside the repo).
**Method:** judged on what *runs*, not on what the ADL says. The per-assertion detail is the
register in [`GOVERNANCE.md`](GOVERNANCE.md) §3; this file is the verdict per checklist section.

## The checklist sections

| Item | Status | Evidence | Owner | Action | Due |
|---|---|---|---|---|---|
| §1 Business drivers and intent (K09, K14) | Partial | cost and runtime per implementation, measured once (`README.md`, `docs/evidence/`); targets in `capabilities.adl` | Adrian Balaban | trend them: the MCP `get_cost_last_run(run_id)` once built | next review |
| §2 Logical and physical structure (K01, K03) | Pass | `test_boundaries.py` (paths, imports, symlinks), `test_structure.py` (cycles, homes, contract copies) in `adl-test` | Adrian Balaban | keep the allowlists shrinking-only | — |
| §3 ADL and documentation (K02, K06) | Pass | `test_adl_lint.py`, `test_adl_hygiene.py`, `test_adl_inventory.py`: form, unique tags, resolvable bindings, single constant homes, one register row per tag | Adrian Balaban | none | — |
| §4 Data architecture and durability (K07, K08) | Partial | the balance, settle and reconciliation run in `make conformance-all` (local); access rules declared in `data-governance.adl` | Adrian Balaban | C13 — the JDBC/IAM access scan | phase 4 |
| §5 Distributed coupling and quanta (K10, K11) | Partial | quanta bound per lane in `static-coupling.adl`; every edge declared in `system.adl` | Adrian Balaban | C12 — the Terraform quanta scan | phase 4 |
| §6 Workflows, sagas and contracts (K05, K12) | Partial | the schema contract plus `test_damage_cases.py` in CI; the saga orchestrator runs in conformance | Adrian Balaban | C14 — contract churn, weekly | phase 5 |
| §7 Code quality (K04) | Partial | thresholds declared and owned; no complexity tool runs yet | Adrian Balaban | C9/C10 in report mode first, then gate | phase 4 |
| §8 Evolvability (K05, K14, K15) | Partial | residues asserted (`architecture-style.adl`); `verify-project2` runs locally | Adrian Balaban | C15 — extend-not-rewrite as a PR check | phase 5 |
| §9 Agentic AI (K17) | Partial | scope declared (`CLAUDE.md`, `governance.adl`); no agent-trailer convention yet | Adrian Balaban | pick `Co-Authored-By`, then C16/C17 | phase 5 |
| §10 Fitness functions and governance (K06, K16) | Partial | one register row per assertion with owner, source, trigger, status (R1); an expired exemption fails the harness (R3) | Adrian Balaban | C9–C17 land the `proposed` rows | phases 4–5 |
| §11 Teams and enterprise alignment (K16, K18) | Partial | the team ADL in `governance.adl` plus `.github/CODEOWNERS` (G4) | Adrian Balaban | C16/C17 read the history | phase 5 |
| §12 Minimum release gate | Partial | see below — 4 pass, 5 partial, 1 fails | Adrian Balaban | phases 4–5 | next review |

## The minimum release gate (checklist §12)

| Item | Status | Evidence | Owner | Action | Due |
|---|---|---|---|---|---|
| 1. Driving capabilities have measurable targets | Pass | `capabilities.adl`; the cost table in `README.md` | Adrian Balaban | trend them | next review |
| 2. Repository and deployment structure match the architecture | Pass | C2, C3, C4 in `adl-test` | Adrian Balaban | none | — |
| 3. Forbidden dependencies and cycles are checked | Pass | `test_boundaries.py` (sharing pinned), `test_structure.py` (acyclic) | Adrian Balaban | close the Maven-GAV gap noted in `test_boundaries` | next review |
| 4. Data ownership and sensitive-data access are enforced | Fail | rules declared; no scan runs (C13 is `proposed`) | Adrian Balaban | C13 | phase 4 |
| 5. Critical data survives downstream crashes; reconciliation proves it | Partial | conformance balance + settle run locally; the mock-outage evidence in `docs/evidence/` | Adrian Balaban | E2 lifts the balance into CI | phase 4 |
| 6. Synchronous dependencies do not invalidate scale or availability | Partial | every edge declared sync/async (`system.adl`); matching observed calls is an exercise | Adrian Balaban | tracing on a live run | next review |
| 7. Distributed workflows and contracts have explicit ownership and failure handling | Partial | the saga, the DLQ and the closed reasons are modelled (`workflows.adl`, `data-governance.adl`) | Adrian Balaban | C14 | phase 5 |
| 8. The hardest load-bearing fitness function runs automatically | Partial | the comparator and the architecture harness run on every PR; the balance itself needs podman | Adrian Balaban | E2 | phase 4 |
| 9. Agent-generated changes stay within declared boundaries | Partial | scope declared; boundaries enforced by C2/C3, but agents are not identifiable in history | Adrian Balaban | trailer convention, then C16/C17 | phase 5 |
| 10. Every failure has a disposition | Pass | `GOVERNANCE.md` §4; `EXEMPTIONS.md` with expiry; the R3 lint | Adrian Balaban | none | — |

**The one Fail** is gate 4: the data-access rules are declared but not scanned — C13 is the
fix, and it is why §4 stays Partial.

Next pass: at the end of the kata series, or at any major architecture change. The build order
for the missing checks lives in [CHECKS-PLAN.md](CHECKS-PLAN.md); the per-assertion detail in
[GOVERNANCE.md](GOVERNANCE.md).
