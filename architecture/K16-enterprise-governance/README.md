# K16. Enterprise governance + the MCP context

## Scenario (5 min)
The bank's IT department mandates "one approved stack for every migration"; ETL-R arrives with 3 stacks and evidence. What strategy is this, and what stays centrally governed?

## Source material
- `SECTIONS.md` (pages 47–48 + 54–55 of the masterclass)
- `make-the-grade-pages-1587-1600/` (enterprise governance: technology and contracts)
- `make-the-grade-pages-1490-1500/` (enterprise governance: what stays central)
- `cost-estimate.md` (real costs on GCP)
- Diagrams: `docs/diagrams/00-overview.svg`

## Tasks (10 min, individual-discussion-team rounds)

1. **What strategy this is (Classic Prescriptive) and what it costs; which "benefits" are in fact accidental complexity.**
2. **The Distributed → Durable Interface recommendation:** what stays centrally governed (identity/observability/integration contracts) and at which level.
3. **SBOMs at build time (`pom.xml`, `requirements.txt`)** exposed over MCP – not an inventory in the ADL.
4. **Push or pull for the Target System contract (Kafka confirmations); who owns the contract and who runs the check.**