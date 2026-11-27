# Architecture as code — ETL-R katas, Neal Ford masterclass 2026

Seventeen architecture katas built on the ETL-R repo: each starts from a scenario (5 min),
reads real repo material, and is solved in *individual–discussion–team* rounds (10–15 min).
The ADL from the answers is consolidated by family in `adl/`; the register lives in
`GOVERNANCE.md`, the checklist mapping in `REVIEW.md`.

## How to use a kata

- `README.md` — the exercise: scenario, source material (real repo files), tasks, and a starting "ADL deliverable"
- `answers.md` — the solutions, including ADL blocks (definitions and assertions)
- `adl/` — the ADL consolidated by family, with full headers and `[Kxx-n]`-tagged assertions
- `GOVERNANCE.md` — the fitness-function register, the tag scheme, the failure dispositions
- `REVIEW.md` — the katas against the Architecture as Code checklist sections
- `diagrams/` — an index into `docs/diagrams/`
- `tests/` + `make adl-test` — the CI harness: register lint, contract derivation [K02-1], lane boundaries [K03-1..3]

## The katas

| # | Kata | Theme |
|---|------|------|
| [K01](K01-logical-vs-implementation/) | Logical vs. implementation architecture | reading the repo as architecture; the domains ADL; an agent's context for a fourth implementation |
| [K02](K02-contract-library/) | The contract as a shared library | `DEFINE LIBRARY Contracts`; a fitness function on `artefacts.json` byte-identity; the "IS DEPENDENT ON" trap |
| [K03](K03-repo-boundaries/) | Boundaries between implementations in the monorepo | explicit vs. implicit ADL; the anti-cycle rule; shared vs. duplicated; `verify-project2` |
| [K04](K04-code-quality/) | Code quality and fitness functions | cyclomatic complexity; A / I / D on the main sequence; an ADL with thresholds |
| [K05](K05-in-flight-change/) | Change in flight | branch vs. toggle vs. config (mapping-YAML); strict vs. permissive contracts; the anti-tautology ADL |
| [K06](K06-FFDA/) | FFDA — Fitness Function Driven Architecture | load-bearing / reject / not-now verdicts; "ADL before code"; the LMAX style; exemptions with owner and expiry |
| [K07](K07-data-topology/) | The data topology | monolith / domain / dedicated; the topology ADL; `reject_log` as a complete DLQ |
| [K08](K08-govern-data-topology/) | Governing the data topology | static / dynamic / platform analysis; legitimate paths that respect the topology; the MCP `get_balance(run_id)` |
| [K09](K09-business-environment/) | The business environment and cost | each implementation's reaction to the S1–S6 scenarios; the cost ADL; a defensive recommendation + a recovery FF |
| [K10](K10-architectural-quantas/) | Architectural quanta | static coupling points per implementation; the Domain Broker; `DEFINE QUANTUM` |
| [K11](K11-dynamic-coupling/) | Dynamic coupling and entanglement | `DEFINE COMMUNICATION`; a READONLYREPLICA for reference data; what you lose without Kafka |
| [K12](K12-run-as-saga/) | The run as a saga | Communication / Coordination / Consistency; "atomic + choreography" becomes an orchestrator; regeneration guardrails |
| [K14](K14-quantifying-capabilities/) | Quantifying capabilities | Simplicity / Deployability / Scalability / Cost; trend vs. a single run; the composite Maintainability ADL |
| [K15](K15-constant-change/) | Constant change | the S1–S6 stressor matrix; residues (contracts-as-config, a pinned oracle); `ASSERT(adding a project modifies only spec/contracts/)` |
| [K16](K16-enterprise-governance/) | Enterprise governance + the MCP context | Classic Prescriptive vs. Distributed → Durable Interface; an SBOM at build time, not an ADL inventory; push vs. pull |
| [K17](K17-agentic-ai/) | Agents and delegated judgment | the generation scope = one quantum; deterministic checks vs. an LLM judge; a DLQ with a human path; MCP tools |
| [K18](K18-teams-git/) | Teams and Git history | Conway's Law at three teams; a team ADL with agents as members; findings on real Git history; agent effectiveness |

*Note: the numbering follows the masterclass agenda; K13 does not exist in this set.*

## Conventions

- The ADL blocks are exercise pseudo-syntax (`DEFINE` / `ASSERT`), not a real parser. The consolidation in `adl/` carries `[Kxx-n]` tags that trace the source kata.
- The folder structure follows the `architecture/` example from the total-recall project: `adl/` + `diagrams/` + `GOVERNANCE.md` + `REVIEW.md`.
- References to `SECTIONS.md` and `make-the-grade-pages-*` point to the masterclass material; it is not part of the repo.
- The cost/time/resource numbers come from `docs/evidence/` and `cost-estimate.md` (real measurements on GCP).