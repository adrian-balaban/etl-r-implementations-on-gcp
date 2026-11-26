# K12. Run-ul ca saga

## Scenariu (5 min)
Un run = 8 stațiuni peste 3-4 unități de deployment; nu poate să se piardă nimic; agenții vor regenera pipeline-uri anul viitor.

## Material sursă
- `spec/PIPELINE.md` (8 stațiuni fixe, echilibrul `src_read = written + rejected`)
- `make conformance-all` (testează toate implementările vs. oracol)
- `make-the-grade-pages-1432-1458/` (close-test workflow tasks)
- Diagrame: `docs/diagrams/03-two-doors.svg`, `docs/diagrams/08-stage-mapping.svg`

## Task-uri (10 min, runde individual-discussion-team)

1. **Cele 3 forțe:** Communication=asincron (topic-uri), Coordination=Orchestrated (Cloud Run job / Composer DAG — cine e orchestratorul la `flink` vs. `beam`?), Consistency=Eventual verificată (recon balance + settle) – argumentele pe cerințe.
2. **"Atomic + choreography" — ce devine în realitate și de ce e neguvernabil.**
3. **ADL:** `DEFINE SAGA Migration Run Saga` + contractojo strict pentru documentul țintă (JSON Schema — consumer-driven confirmations).
4. **Scope de regenerare** = cuanta / job-ul, cu tabel de guardrails: behavior (conformance 68/68), static coupling (fără cuplaj nou), dynamic (tot asincron), contract (schema intact), capability (timp de run).