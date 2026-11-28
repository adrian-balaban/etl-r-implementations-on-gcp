# Most assertions still lack a check

**Repo:** `etl-r-implementations-on-gcp` · **Baseline:** `main` @ `7ec2db6` (2026-11-27) · **Owner:** Adrian Balaban
**Checklist:** `ARCHITECTURE_AS_CODE_CHECKLIST.md` from the masterclass material, outside the repo.
**Scope:** `architecture/` and `.github/`, judged on what runs, not on what the ADL says.
**Companions:** `GOVERNANCE.md` holds the register; `REVIEW.md` maps checklist to katas.

`adl/` is the single source, but most of its assertions have no enforced check.
Close the gap in two moves: fix the ADL defects (§3), then build the checks (§4–§6).
Every new check is deterministic and runs in CI without podman or GCP.
Each ships one passing case and one failing case, the course rule behind [K04-5].
Each carries the `[Kxx-n]` tag of the assertion it underwrites.

## 1. Where the repo stands

Verdicts per checklist section, on evidence.

| § | Section | Verdict | Why |
|---|---|---|---|
| 1 | Business drivers | Partial | cost and runtime measured once; no trend |
| 2 | Structure | Partial | boundaries enforced; no cycle or orphan check |
| 3 | ADL and docs | Partial | form linted; bindings and duplicate tags unchecked |
| 4 | Data and durability | Partial | balance runs locally; access rules unchecked |
| 5 | Quanta | Gap | quanta declared; no Terraform or IAM analysis |
| 6 | Workflows, contracts | Partial | schema exists; no churn check |
| 7 | Code quality | Gap | thresholds declared; no complexity tool runs |
| 8 | Evolvability | Partial | `verify-project2` exists; local only |
| 9 | Agentic AI | Partial | scope declared; agent commits not identifiable |
| 10 | Fitness functions | Partial | pass and fail cases; no owner or trigger |
| 11 | Teams | Gap | team ADL declared; no history check, no CODEOWNERS |
| 12 | Release gate | Partial | gates 3 and 8 hold; others local or proposed |

## 2. Five checks already run

| Assertion | Enforced by |
|---|---|
| ADL form: headers, an `ASSERT`, tags match kata folders | `adl-test.yml` → `test_adl_lint.py` |
| [K03-1..3] lane boundaries: paths, Java imports, symlinks | `adl-test.yml` → `test_boundaries.py` |
| [K02-1] artefact names derive from `artefacts.json` | `adl-test.yml` → `test_contracts.py` |
| [K02-3] the pinned `ACCOUNT.src` sha256 | `spec-test.yml` → `spec/conformance/test_compare.py` |
| [K04-5] the comparator fails on damage | `spec-test.yml` → `make spec-test` |

`GOVERNANCE.md` §3 does not yet cite the [K02-3] row; R1 fixes that.

## 3. Fix the ADL first

Each defect was verified on the baseline. Defective rules make defective checks.

| ID | File | Defect | Fix |
|---|---|---|---|
| ✅ D1 | `data-governance.adl`, `system.adl` | `[K11-2]` tags two different assertions | retag one; C1 lints uniqueness |
| ✅ D2 | `data-governance.adl` | [K07-3] names Customer and Ticketing Database | restate on `account_target` and confirmations |
| ✅ D3 | `data-governance.adl`, `static-coupling.adl` | source-DB accessors disagree across three places | align with each lane's real reader |
| ✅ D4 | `static-coupling.adl` | one `account_source_db` name in two quanta | bind per lane, or declare the sharing |
| ✅ D5 | `static-coupling.adl` | `etlr_source AS source tables` binds to prose | bind to a real schema name |
| ✅ D6 | `architecture-style.adl` | `TargetSchema` path lacks `spec/contracts/` | fix the path; C1 lints bindings |
| ✅ D7 | `system.adl`, `workflows.adl` | the three edges are declared twice | keep them in `system.adl` only |
| ✅ D8 | `capabilities.adl` | redefines `MAX_CC`, `MAX_DISTANCE`; [K14-5] repeats [K04-4] | keep thresholds in `code-quality.adl` |
| ✅ D9 | `governance.adl` | "five commits" is stale; history holds six | drop the count |
| ✅ D10 | `GOVERNANCE.md`, `REVIEW.md` | baseline says `bfe7563` | update at each review |
| ✅ D11 | `tests/test_boundaries.py` | flink pins a reference to flink-jdbc | reword `secrets/main.tf:35`; drop the pin |

D3 in detail: `data-governance.adl` grants Beam the source DB through `WITHIN`.
[K07-1] lists only two accessors. `static-coupling.adl` gives Beam no source DB at all.

D11 in detail: the reference is only a Terraform description string.
Today it makes flink and flink-jdbc point at each other, a cycle on paper.

## 4. The checks to add

### 4.1 Cheap and deterministic

| ID | Assertion | Check | Trigger |
|---|---|---|---|
| ✅ C1 | ADL hygiene | unique tags; `AS path` resolves; constants defined once | PR |
| ✅ C2 | [K03-4] no cycles | lane graph from paths and imports is acyclic | PR |
| ✅ C3 | [K01-1] every file has a home | files outside components need an allowlist entry | PR |
| ✅ C4 | [K02-2] one contract copy | lane `contracts`/`fixtures` are symlinks into `spec/` | PR |
| ✅ C5 | [K06-4] `.FLG` outside the bundle | `bundle.py` output: flag present, not encrypted | PR |
| ✅ C6 | [K05-4 K05-5] no empty tests | each Python and JUnit test asserts something non-constant | PR |
| ✅ C7 | [K16-2] no inventory in ADL | forbid version numbers and stray `USING` | PR |
| ✅ C8 | [K04-5] damage cases exist | each comparator failure class has a damaged fixture | PR |

C2 fails until D11 lands. Land D11 first, then C2.
C3 starts with the lane-root files `[K01-1]` already names: `Makefile`, `pom.xml`, `Dockerfile.*`.
C4 holds today; the check keeps it true.
C6 covers 37 JUnit test files, not only Python; JUnit counts `assert*` and `verify`.

### 4.2 Checks that need a tool

| ID | Assertion | Check | Trigger |
|---|---|---|---|
| C9 | [K04-1..3] complexity | `radon` for Python, Lizard for Java; above `MAX_CC` fails | PR |
| C10 | [K04-4] main sequence | A, I, D per module; `D ≥ 0.3` fails | PR |
| C11 | [K16-1 K16-3] SBOM | CycloneDX for Maven and pip; non-empty, uploaded | PR |
| C12 | [K10-1 K08-4] quanta | every Terraform broker and DB claimed by its quantum | PR |
| C13 | [K07-1 K08-1] DB access | JDBC URLs and IAM bindings match declared accessors | PR |

`MAX_CC` is 10, and 5 for agent-generated code.
Run C9 and C10 in report mode for a week, then set the gate.
A number reported without a gate is not a fitness function (checklist §7.1).

### 4.3 History checks on a schedule

| ID | Assertion | Check | Trigger |
|---|---|---|---|
| C14 | [K02-4] shared churn | `spec/contracts/` commits per month ≤ 2 | weekly |
| C15 | [K15-1] a project adds only contracts | a new mapping's commit touches only `spec/contracts/` | PR |
| C16 | [K18-2 K18-3] commit authority | authors in team ADL; files inside their domain | weekly |
| C17 | [K18-5] agent conflicts | agent-trailer merges with conflicts, past month | monthly |

C16 and C17 need agents visible in history. No commit carries an agent trailer today.
Pick one convention, such as `Co-Authored-By`, and declare the real agents in `governance.adl`.
Until more hands arrive, both checks pass trivially.

### 4.4 Checks that stay local, manual or exercise

| Assertion | Status | Reason |
|---|---|---|
| [K06-1] the balance | local, conformance | needs podman; see E2 |
| [K03-5..7] `verify-project2` | local, beam lane | needs the beam toolbox; see E2 |
| [K09-*] [K14-1..4] cost, runtime | manual, per GCP run | billable; nothing runs on GCP unasked |
| [K11-1 K12-1] edges match runtime | exercise | needs tracing on a live run |
| [K05-3] `MAX_ENDPOINTS` | exercise | no versioned endpoints exist |
| the MCP tools, `GOVERNANCE.md` §6 | proposed | building them is the katas' subject |

Mark each in the register, so no reader mistakes it for a gate.

## 5. Harden `.github/`

| ID | Change | Why |
|---|---|---|
| ✅ G1 | pin actions to commit SHAs | `checkout@v4` and `setup-uv@v5` are mutable tags |
| G2 | pin pytest in the root `Makefile` | `--with pytest` floats; CI is not reproducible |
| ✅ G3 | add a `concurrency` group per ref | cancel stale runs on new pushes |
| G4 | add `CODEOWNERS` from the team ADL | checklist §11; the team map gets teeth |
| G5 | add Dependabot: actions, Maven, pip | pinned versions need update signals |
| G6 | add a scheduled workflow for C14–C17 | history checks need a time frame |
| E2 | try `conformance-flink` on a podman runner | lifts [K06-1] into CI; measure runtime first |

`.github/modernize/java-upgrade/` is untracked local tooling; its `.gitignore` ignores everything.
It needs no change. It matters to K17 only if its JSONL becomes the agent-audit source.

## 6. Make the register auditable

| ID | Change | Checklist |
|---|---|---|
| ✅ R1 | one row per assertion: owner, source, trigger, status | §10.5 |
| R2 | an `EXEMPTIONS.md`: owner, rationale, scope, expiry | §10.9 |
| R3 | lint: an expired exemption fails `adl-test` | §10.9, §12.10 |
| R4 | turn `REVIEW.md` into a review record with verdicts | the record template |

R1 status values: `CI`, `local`, `scheduled`, `manual`, `exercise`, `proposed`.
A lint can then prove every tag has a row.

## 7. Order of work

| Phase | Items | Done when |
|---|---|---|
| 1 | D1–D11 | `make adl-test` green; register and index updated |
| 2 | C1–C8, R1 | each check has a passing and a failing test |
| 3 | G1–G5, R2–R4 | CI green; `REVIEW.md` holds verdicts |
| 4 | C9–C13, E2 | tools report first, then gate |
| 5 | C14–C17, G6 | the scheduled workflow runs and reports |

Each phase is one commit or a small series.
Run `make spec-test` and `make adl-test` before each.
E2 also needs `make conformance-all` locally first.

A failed assertion is feedback, not automatically a bug (`GOVERNANCE.md` §4).
Each failure gets one disposition: fix the code, revise ADL and test together, or exempt.
An exemption names an owner, rationale, scope and expiry.

## 8. Progress

Append-only log, one line per landed point; the ✅ marks the tables above.

- **D1** — 2026-11-28 — `[K11-2]` tags one assertion: the RUNTIME_200K copy left `system.adl` (K11-2 stays on the shared-database channel; the runtime lives at `[K09-5]`)
- **D2** — 2026-11-28 — `[K07-3]` restated on `account_target` and the confirmations topic
- **D3** — 2026-11-28 — `account_source_db`'s `WITHIN` now matches its accessors (Flink + FlinkJdbc); Beam reads the extract, owns no source DB
- **D4** — 2026-11-28 — the source DB is bound per lane (`mig-flink-pg` / `mig-flink-jdbc-src`); jdbc's target DB is declared (`mig-flink-jdbc-lane`)
- **D5** — 2026-11-28 — the prose binding replaced by `DEFINE SCHEMA SourceSchema AS etlr_source` (`sql/source-init.sql`)
- **D6** — 2026-11-28 — `TargetSchema` resolves: `spec/contracts/schemas/target-account.schema.json`
- **D7** — 2026-11-28 — the three edges live only in `system.adl`; the saga references them instead of restating
- **D8** — 2026-11-28 — `MAX_CC`/`MAX_DISTANCE` have one home (`code-quality.adl`); `[K14-5]` merged into `[K04-4]`
- **D9** — 2026-11-28 — the stale commit count dropped from `governance.adl`
- **D10** — 2026-11-28 — `GOVERNANCE.md` and `REVIEW.md` baselines moved to `7ec2db6`
- **D11** — 2026-11-28 — `secrets/main.tf` description reworded; the flink→flink-jdbc pin dropped (the lane graph is acyclic on paper again)
- **C1** — 2026-11-28 — `test_adl_hygiene.py`: tags unique, `AS` bindings resolve, constants have one home (three passing and three failing cases)
- **C2** — 2026-11-28 — `test_structure.py`: the lane graph (path and reactor references) is acyclic; the detector fires on a two-lane cycle
- **C3** — 2026-11-28 — `test_structure.py`: every lane-root entry is a declared component or a reasoned allowlist entry (`apps`, the two flink variants, shared infra dirs)
- **C4** — 2026-11-28 — `test_structure.py`: each lane's `contracts`/`fixtures` is a symlink into `spec/`; a real directory fails
- **C5** — 2026-11-28 — `test_bundle.py`: a real `bundle.py` run puts `ACCOUNT.FLG` beside the archive, never inside; `--no-flg` makes the check fire
- **C6** — 2026-11-28 — `test_test_quality.py`: Python AST (no empty/tautological asserts; `pytest.raises` counts) and JUnit (`@Test` body needs assert/verify/fail/check); one delegated guard named with its reason
- **C7** — 2026-11-28 — `test_adl_inventory.py`: no technology+version pairs and no unreasoned `USING` in `adl/`
- **C8** — 2026-11-28 — `test_damage_cases.py`: the nine comparator failure classes are pinned; each must still exist and drive the `dump` fixture
- **R1** — 2026-11-28 — `GOVERNANCE.md` §3 is one row per assertion (tag, owner, source, trigger, status); the lint in `test_adl_lint.py` fails when a tag loses its row
- **G1** — 2026-11-28 — both workflows pin `actions/checkout` v4.4.0 and `astral-sh/setup-uv` v5.4.2 by commit SHA
- **G3** — 2026-11-28 — a per-ref `concurrency` group cancels stale runs in both workflows
<!-- progress-end -->
