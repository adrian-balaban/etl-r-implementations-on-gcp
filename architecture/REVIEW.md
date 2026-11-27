# Review — the katas against the Architecture as Code checklist

**Repo:** `etl-r-implementations-on-gcp` · **Baseline:** `main` @ `bfe7563` (2026-11-27) · **Owner:** Adrian Balaban
**Checklist:** `ARCHITECTURE_AS_CODE_CHECKLIST.md` from the masterclass material (outside the repo).

Framing: the K01–K18 series exercises the checklist against the repo's real material. This
review is a **mapping** of exercise ↔ checklist section ↔ evidence, not an audit with
verdicts. The verdicts belong to the team round and to the register in `GOVERNANCE.md`.

| Checklist section | Katas | The evidence in the repo |
|---|---|---|
| 1. Business drivers and intent | K09, K14 | the cost table in the root `README.md`; `docs/evidence/` |
| 2. Logical and physical structure | K01, K03 | `implementations/` + `spec/`; `verify-project2` |
| 3. ADL and documentation | K02, K06 | `spec/contracts/`; `architecture/adl/` |
| 4. Data and durability | K07, K08 | the seven tables in `spec/PIPELINE.md`; conformance |
| 5. Quanta and distributed coupling | K10, K11 | `terraform/` per implementation; the evidence: 71/81/75 |
| 6. Workflows, sagas, contracts | K05, K12 | `docs/diagrams/03-two-doors.svg`; the Kafka topics |
| 7. Code quality | K03, K04 | the comparator `spec/conformance/compare.py` and its tests |
| 8. Evolvability under change | K05, K14, K15 | `docs/diagrams/14-extend-not-rewrite.svg`; the mapping-YAML |
| 9. Agentic AI and delegated judgment | K17 | `CLAUDE.md`; the closed reasons in `reject_log` |
| 10. Fitness functions and governance | K06, K16 | `make spec-test`; `make conformance-all` |
| 11. Teams and enterprise alignment | K16, K18 | the real `git log`; K18's team ADL |
| 12. The minimum release gate | all | a synthesis of the above, for the team discussion |

Next pass: at the end of the kata series, or at any major architecture change.