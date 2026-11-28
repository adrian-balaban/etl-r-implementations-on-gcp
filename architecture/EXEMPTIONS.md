# Exemptions — a failed assertion with an owner, a rationale, scope and expiry

Every row here is a deliberate, time-bounded exception to an assertion (checklist §10.9).
An expired row fails `make adl-test` (`architecture/tests/test_exemptions.py`, R3): renew it
with a new date, or fix the underlying assertion. An exemption does not change a register
row's status — a `proposed` assertion stays `proposed` while it is exempted.

| Scope | Owner | Rationale | Expires |
|---|---|---|---|
| C6 — `TargetSchemaValidatorTest.coversEveryConstraintDeclaredInTheSchema` | Adrian Balaban | The assertion throws inside `TargetSchemaValidator.checkCoversSchemaConstraints()`; restating it as a local assert would duplicate the validator. The JUnit heuristic names this pair in `JUNIT_ASSERT_DELEGATES` (`tests/test_test_quality.py`). | 2027-05-28 |
| C13 — `flink/terraform/modules/ttl_guard`: `roles/cloudsql.admin` | Adrian Balaban | The TTL sweeper must delete the billable parts of the environment after expiry; Cloud SQL has no narrower predefined role that can delete backups and instances. The sweeper script only touches names under `name_prefix` and cannot create anything. | 2027-05-28 |
