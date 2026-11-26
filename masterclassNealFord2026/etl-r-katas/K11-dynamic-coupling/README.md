# K11. Dynamic coupling și entanglement

## Scenariu (5 min)
Agentul "simplifică debugging-ul": `produce` cheamă `load` sincron (REST) în loc de Kafka. De asemenea, "streaming mode is a defect" — de ce batch-ul bounded e un principiu de arhitectură (snapshot, balanță, recon)?

## Material sursă
- `spec/PIPELINE.md` (8 stațiuni fixe, `streaming mode is a defect`)
- `docs/evidence/README.md` ("What went wrong", varianța Kafka, mock-ul picat)
- `make conformance-all` (testează toate implementările vs. oracol)
- `make-the-grade-pages-1379-1398/` (dynamic coupling tasks)
- Diagrame: `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Capability ADL pentru `produce` vs. `load`** (utilizatori, timp țintă) – care conflictează cu chemarea sincronă; explică dynamic quantum entanglement.
2. `DEFINE COMMUNICATION` pentru fiecare relație (orchestrator→gate: sincronă; produce→load: asincronă Kafka; mock→recon: asincronă confirmări) + aserțiunea care ține agenții la ea.
3. `enrich` cu date de referință (`reference/branches.json`): alternativă cu READONLYREPLICA și restul de cuplaj static.
4. Ce pierde run-ul pe care Kafka îl protejează: fault tolerance la mock-ul picat.