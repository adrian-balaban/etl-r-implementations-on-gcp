# K17. Agents and delegated judgment

## Scenario (5 min)
One agent builds `implementations/spark-dataproc/` from `spec/PIPELINE.md` + the ADL; another "optimizes" the conformance tests. Is the generation scope a quantum, or is it not?

## Source material
- `SECTIONS.md` (pages 49–50 + the GenAI kata)
- `make-the-grade-pages-1520-1550/` (essays by consensus)
- `make conformance-all` (tests every implementation)
- `spec/fixtures/ACCOUNT.src` + `manifest.json`
- Diagrams: `docs/diagrams/03-two-doors.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **The generation scope = one quantum, not "refactor everything";** the ADL's `DEFINE AGENT Spark Builder` + assertions like "agents cannot add cross-implementation dependencies or connections to undeclared databases".
2. **Deterministic checking always comes first:** conformance/the balance, not an LLM judge; where human judgment remains (random probes, human review — the rate).
3. **`reject_log` = the system's DLQ:** closed reasons + a human path (fixing the source or the mapping); the last mile that cannot be automated.
4. **An MCP server over the ETL-R governance context:** `get_balance(run_id)`, `get_conformance()`, `get_cost_last_run()`; who runs them, and how often?