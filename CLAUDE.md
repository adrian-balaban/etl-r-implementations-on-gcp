# CLAUDE.md

Monorepo: one ETL-R pipeline defined once in `spec/`, implemented in `implementations/`.

- **Edit shared things in `spec/` only.** `contracts/`, `fixtures/` and `harness/generate.py`/`bundle.py` appear
  inside each implementation as relative symlinks; edit the target, never a copy. Do not hand-edit
  `spec/fixtures/ACCOUNT.src` (sha256 pinned in `fixtures/manifest.json`).
- **Run build and test commands from inside the implementation** (`implementations/<name>/`); each keeps its own
  build, tooling and `CLAUDE.md`. Every implementation answers `make help`:
  `generate`, `run`, `test`, `conformance`, `verify`.
- **Before claiming a change is safe:** `make spec-test` (the comparator), and `make conformance-all` (every
  lane against the oracle; local, no GCP). Docker builds need `--build-context spec=<path to spec/>`.
- **Diagrams live only in `docs/diagrams/`:** shared, implementation-neutral ones at the top, implementation detail in
  `docs/diagrams/<implementation>/`; index in `docs/diagrams/README.md`. Render with `docs/diagrams/render.sh`
  (PlantUML 1.2026.8, smetana, C4 from the stdlib) and commit `.puml` + `.svg` together.
- **Canonical names:** stages `extract gate intake curate enrich produce load recon`; storage prefix `etlr_`;
  GCP service-account identities keep `loader-app` / `recon-service` (GCP needs ≥ 6 characters).
- **Language:** English only.
- **Writing:** titles are claims of 2–6 words ("Two doors, no third"). Never a diagram type, a file path or a bare label.
  Index pages keep plain labels. One idea per paragraph. Keep numbers, codes and examples; cut repeats and history.
  One clause per sentence: at most 20 words in prose, 12 in a table cell, 8 in a diagram label.
- Nothing is pushed, and nothing billable runs on GCP, without asking first.
- Never commit unless asked (the approved plan counts as asking for the commits it names).
