# K16. Guvernanță enterprise + context MCP

## Scenariu (5 min)
Departamentul IT al băncii mandatează "un singur stack aprobat pentru toate migrările"; ETL-R vine cu 3 stive și evidence. Ce strategie e și ce rămâne guvernat central?

## Material sursă
- `SECTIONS.md` (paginile 47–48 + 54–55 din masterclass)
- `make-the-grade-pages-1587-1600/` (enterprise governance: technology and contracts)
- `make-the-grade-pages-1490-1500/` (enterprise governance: what stays central)
- `cost-estimate.md` (cost-uri reale pe GCP)
- Diagrame: `docs/diagrams/00-overview.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Ce strategie e (Classic Prescriptive) și ce costă; ce "beneficii" sunt de fapt accidental complexity.**
2. **Recomandare Distributed → Durable Interface:** ce rămâne guvernat central (identități/observabilitate/integration contracts) și la ce nivel.
3. **SBOM-uri la build (`pom.xml`, `requirements.txt`)** expuse la MCP – nu inventar în ADL.
4. **Push sau pull la contractul cu Target System (Kafka confirmations); cine deține contractul și cine rulează verificarea.**