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
| §4 Data architecture and durability (K07, K08) | Partial | the balance, settle and reconciliation run locally; `tests/test_db_access.py` now enforces declared database names and the IAM exemption | Adrian Balaban | trace the live access paths on a real run | next review |
| §5 Distributed coupling and quanta (K10, K11) | Partial | quanta bound per lane; `tests/test_quanta.py` checks every Terraform store against its quantum | Adrian Balaban | trace the runtime edges on a live run | next review |
| §6 Workflows, sagas and contracts (K05, K12) | Partial | the schema contract and damage cases in CI; `test_history.py` watches churn and mapping scope weekly | Adrian Balaban | trace the saga's failure handling on a live run | next review |
| §7 Code quality (K04) | Partial | thresholds declared and owned; the complexity and main-sequence ratchets run in `adl-test`, with today's 30 hotspots and the three off-sequence modules pinned | Adrian Balaban | shrink the pins: refactor one hotspot per change | next review |
| §8 Evolvability (K05, K14, K15) | Partial | residues asserted; `test_history.py` keeps a mapping commit inside `spec/contracts/`; `verify-project2` stays local | Adrian Balaban | none — keep `verify-project2` local | — |
| §9 Agentic AI (K17) | Partial | scope declared (`CLAUDE.md`, `governance.adl`); C16 reads authorship weekly; no agent trailer exists yet | Adrian Balaban | adopt the trailer convention when an agent first commits | next review |
| §10 Fitness functions and governance (K06, K16) | Partial | one register row per assertion; the C-series landed every `proposed` row; an expired exemption fails the harness | Adrian Balaban | the K17 agent-scope gate awaits the trailer convention | next review |
| §11 Teams and enterprise alignment (K16, K18) | Partial | the team ADL in `governance.adl`, `.github/CODEOWNERS` (G4), and `tests/test_history.py` reading churn, mapping scope, authority and agent merges weekly | Adrian Balaban | the domain-scope leg bites once more members exist | next review |
| §12 Minimum release gate | Partial | see below — 5 pass, 5 partial, none fails | Adrian Balaban | the E2 measurement closes gate 8 | next review |

## The minimum release gate (checklist §12)

| Item | Status | Evidence | Owner | Action | Due |
|---|---|---|---|---|---|
| 1. Driving capabilities have measurable targets | Pass | `capabilities.adl`; the cost table in `README.md` | Adrian Balaban | trend them | next review |
| 2. Repository and deployment structure match the architecture | Pass | C2, C3, C4 in `adl-test` | Adrian Balaban | none | — |
| 3. Forbidden dependencies and cycles are checked | Pass | `test_boundaries.py` (sharing pinned), `test_structure.py` (acyclic) | Adrian Balaban | close the Maven-GAV gap noted in `test_boundaries` | next review |
| 4. Data ownership and sensitive-data access are enforced | Pass | `tests/test_db_access.py` — declared database names and the IAM exemption | Adrian Balaban | none | — |
| 5. Critical data survives downstream crashes; reconciliation proves it | Partial | conformance balance + settle run locally; the mock-outage evidence in `docs/evidence/` | Adrian Balaban | E2 lifts the balance into CI | phase 4 |
| 6. Synchronous dependencies do not invalidate scale or availability | Partial | every edge declared sync/async (`system.adl`); matching observed calls is an exercise | Adrian Balaban | tracing on a live run | next review |
| 7. Distributed workflows and contracts have explicit ownership and failure handling | Partial | the saga, the DLQ and the closed reasons are modelled (`workflows.adl`, `data-governance.adl`) | Adrian Balaban | C14 | phase 5 |
| 8. The hardest load-bearing fitness function runs automatically | Partial | the comparator and the architecture harness run on every PR; `conformance.yml` (E2) can run the balance on a runner, timed, pending its first measurement | Adrian Balaban | run E2 once and read the time | next review |
| 9. Agent-generated changes stay within declared boundaries | Partial | scope declared; boundaries enforced by C2/C3, but agents are not identifiable in history | Adrian Balaban | trailer convention, then C16/C17 | phase 5 |
| 10. Every failure has a disposition | Pass | `GOVERNANCE.md` §4; `EXEMPTIONS.md` with expiry; the R3 lint | Adrian Balaban | none | — |

No gate fails now: gate 4 closed with C13. Gate 8 waits on E2's measured runtime; gates 5–7
and 9 stay Partial by design — live-run tracing, podman, and a trailer convention the repo has
not needed yet.

Next pass: at the end of the kata series, or at any major architecture change. The build order
for the missing checks lives in [CHECKS-PLAN.md](CHECKS-PLAN.md); the per-assertion detail in
[GOVERNANCE.md](GOVERNANCE.md).
