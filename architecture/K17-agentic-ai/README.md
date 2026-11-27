# K17. Agenți și judecată delegată

## Scenariu (5 min)
Un agent construiește `implementations/spark-dataproc/` din `spec/PIPELINE.md` + ADL; alt agent "optimizează" conformance testele. Scope-ul generării e sau nu o cuantă?

## Material sursă
- `SECTIONS.md` (paginile 49–50 + GenAI kata)
- `make-the-grade-pages-1520-1550/` (essays by consensus)
- `make conformance-all` (testează toate implementările)
- `spec/fixtures/ACCOUNT.src` + `manifest.json`
- Diagrame: `docs/diagrams/03-two-doors.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Scope generare = o cuantă, nu "refactor tot";** ADL `DEFINE AGENT Spark Builder` + aserțiuni "agenții nu pot adăuga dependențe cross-implementation sau conexiuni la baze ne-declare".
2. **Verificarea deterministă e mereu prioritară:** conformance/echilibru, nu LLM-judge; unde rămâne judecata umană (probe random, review uman — rata).
3. **`reject_log` = DLQ-ul sistemului:** reason-uri închise + cale umană (rectificarea sursei/mappingului); ultima milă care nu se poate automatiza.
4. **MCP server la governance context-ul ETL-R:** `get_balance(run_id)`, `get_conformance()`, `get_cost_last_run()`; cine le rulează și cu ce cadență?