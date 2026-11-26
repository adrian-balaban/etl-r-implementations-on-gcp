# ETL-R Katas — Masterclass Neal Ford 2026

17 exerciții de arhitectură (katas) construite pe repo-ul ETL-R: fiecare pornește de la un scenariu (5 min), citește material real din repo și se rezolvă în runde *individual–discussion–team* (10–15 min).

## Cum folosești un kata

- `README.md` — exercițiul: scenariu, material sursă (fișiere reale din repo), task-uri și un „Livrabil ADL" de start
- `answers.md` — soluțiile, inclusiv blocuri ADL (definiții și aserțiuni)

## Lista kata-urilor

| # | Kata | Tema |
|---|------|------|
| [K01](K01-logical-vs-implementation/) | Arhitectură logică vs. implementare | Citirea repo-ului ca arhitectură; ADL-ul domeniilor; contextul unui agent pentru implementarea #4 |
| [K02](K02-contract-library/) | Contractul ca bibliotecă partajată | `DEFINE LIBRARY Contracts`; fitness function pe byte-identitatea `artefacts.json`; capcana „IS DEPENDENT ON" |
| [K03](K03-repo-boundaries/) | Frontiere între implementări în monorepo | ADL explicit vs. implicit; regula anti-ciclu; partajat vs. duplicat; `verify-project2` |
| [K04](K04-code-quality/) | Calitatea codului și fitness functions | Complexitate ciclomatică; A / I / D pe main sequence; ADL cu praguri |
| [K05](K05-in-flight-change/) | Schimbare în zbor | Branch vs. toggle vs. config (mapping-YAML); contracte strict/permeabil; ADL anti-tautologie |
| [K06](K06-FFDA/) | FFDA — Fitness Function Driven Architecture | Verdicte load-bearing / reject / not-now; „ADL înainte de cod"; stilul LMAX; exemption cu owner și termen |
| [K07](K07-data-topology/) | Topologia de date | Monolit / domain / dedicated; ADL-ul topologiei; `reject_log` ca DLQ complet |
| [K08](K08-govern-data-topology/) | Guvernarea topologiei | Analize statică / dinamică / platformă; căi legitime fără încălcarea topologiei; MCP `get_balance(run_id)` |
| [K09](K09-business-environment/) | Mediul de business și cost | Reacția la scenariile S1–S6; ADL de cost; recomandare defensivă + FF de revenire |
| [K10](K10-architectural-quantas/) | Cuante arhitecturale | Puncte de cuplaj static per implementare; Domain Broker; `DEFINE QUANTUM` |
| [K11](K11-dynamic-coupling/) | Dynamic coupling și entanglement | `DEFINE COMMUNICATION`; READONLYREPLICA pentru date de referință; ce pierzi fără Kafka |
| [K12](K12-run-as-saga/) | Run-ul ca saga | Communication / Coordination / Consistency; „Atomic + choreography" devine orchestrator; guardrails de regenerare |
| [K14](K14-quantifying-capabilities/) | Cuantificarea capabilităților | Simplicity / Deployability / Scalability / Cost; trend vs. un singur run; ADL composite Maintainability |
| [K15](K15-constant-change/) | Schimbare constantă | Matricea de stressori S1–S6; reziduuri (contracts-as-config, oracol pinned); `ASSERT(adding a project modifies only spec/contracts/)` |
| [K16](K16-enterprise-governance/) | Guvernanță enterprise + context MCP | Classic Prescriptive vs. Distributed → Durable Interface; SBOM la build, nu inventar în ADL; push vs. pull |
| [K17](K17-agentic-ai/) | Agenți și judecată delegată | Scope-ul generării = o cuantă; verificare deterministă vs. LLM-judge; DLQ cu cale umană; unelte MCP |
| [K18](K18-teams-git/) | Echipe și istoric Git | Conway's Law la 3 echipe; ADL de echipe + agenți ca membri; findings pe istoricul Git; eficiența agentului |

*Notă: numerotarea urmează agenda masterclass-ului; K13 nu există în acest set.*

## Convenții

- Blocurile ADL sunt pseudo-sintaxă de exercițiu (`DEFINE` / `ASSERT`), nu un parser real.
- Referințele `SECTIONS.md` și `make-the-grade-pages-*` trimit la materialul masterclass-ului; nu sunt incluse în repo.
- Numerele de cost/timp/resurse provin din `docs/evidence/` și `cost-estimate.md` (măsurători reale pe GCP).
