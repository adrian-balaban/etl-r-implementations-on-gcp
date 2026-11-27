# K08. Governing the data topology

## Scenario (5 min)
An agent "speeds recon up" and connects its recon service straight to `etlr_extraction` (or reads `account_enriched`) instead of consuming the confirmations. Which assertion breaks?

## Source material
- `docs/evidence/README.md` ("What went wrong", "Limits") — the Kafka variance, the dead mock
- `make conformance-all` (tests every implementation against the oracle)
- `spec/PIPELINE.md` (eight stages, the balance equation)
- `make-the-grade-pages-35-36/` (governing the data topology tasks)
- Diagrams: `docs/diagrams/04-c4-context.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **Which assertion breaks;** what the static analysis inspects (Terraform/IAM + manifests), what the dynamic one (the Kafka flow) and the platform one (Cloud SQL logs) – which catches the break first, and where?
2. **Two legitimate paths** to the same result without breaking the topology.
3. **The reconciliation fitness function for ETL-R:** the balance + `settle` against the confirmations – who runs it, when, and which MCP tool would expose it (`get_balance(run_id)`).
4. **A crash between dequeue and persist** – the Asynchronous Chain of Persistence: synchronous send, client acknowledge mode, last-participant support.