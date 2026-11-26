# K08. Guvernarea topologiei

## Scenariu (5 min)
Un agent "grăbește recon-ul" și își conectează serviciul de recon direct la `etlr_extraction` (sau citește `account_enriched`) în loc să consume confirmările. Care aserțiune se rupe?

## Material sursă
- `docs/evidence/README.md` ("What went wrong", "Limits") – varianța Kafka, mock-ul șteampilat
- `make conformance-all` (testează toate implementările vs. oracol)
- `spec/PIPELINE.md` (8 stațiuni, ecuația de balanță)
- `make-the-grade-pages-35-36/` (governing the data topology tasks)
- Diagrame: `docs/diagrams/04-c4-context.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Care aserțiune se rupe**; ce inspectează analiza statică (Terraform/IAM + manifests), dinamică (flow Kafka) și de platformă (logs Cloud SQL) – care prinde prima și unde?
2. **Două căi legitime** de a obține același rezultat fără încălcarea topologiei.
3. **Funcția fitness de reconciliere** numelui ETL-R: balanța + `settle` contra confirmări – cine o rulează, când, și ce MCP tool ar expune-o (`get_balance(run_id)`).
4. **Crash între dequeue și persist** – Asynchronous Chain of Persistence: synchronous send, client acknowledge mode, last-participant support.