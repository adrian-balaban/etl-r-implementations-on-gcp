# CLAUDE.md

The flink-jdbc lane. Read `spec/PIPELINE.md` ("Source System as a database") before
changing anything here.

- **Shared modules.** The reactor builds `../flink/common`, `../flink/variant-flink-sql-batch-job`,
  `../flink/recon` and `../flink/target-system-mock`. A change to any of them must pass both lanes:
  `make test conformance` here, then `make -C ../flink test conformance`.
- **Shared `target/` directories.** Those modules compile in place under `../flink/*/target/`. Never build this lane
  and `../flink` at the same time.
- **Versions** come only from `../flink/pom.xml` (this lane's parent). Do not declare versions here.
- **The digest formula lives in three places** — `spec/harness/load_db.py`, `gate-jdbc`'s `CanonicalSql`,
  `intake-jdbc`'s `ContentDigest` + the template's canonical line. Change all three together; the pinned values in
  `spec/PIPELINE.md` and `GateJdbcIT` / `JdbcIntakeJobIT` must still pass.
- **`JdbcIntakeJob` is in package `mig.sql`** because `SqlJobSupport` is package-private there.
- **New `*IT.java`** must print `Running …` under `mvn test` (surefire include inherited from `../flink/pom.xml`).
- **Docker builds** compile from source: context `implementations/` (both lanes), `--build-context spec=<abs spec/>`,
  `--ignorefile gcp/docker/build.ignore` — use `gcp/docker/build.sh`.
- **Terraform** reuses `../flink/terraform/modules` by relative path. `modules/cloudsql` and `modules/secrets` take
  names as inputs whose defaults are the Flink lane's; `JdbcTerraformContractTest` pins them. Only `fmt`/`validate`
  are free.
- **`run.sh` exit codes:** gate 3 = not ready (no attempt), gate 4 / recon-jdbc 4 = terminal, other = attempt.
  `JdbcOrchestratorBehaviourTest` runs the real script.
- Never commit or push unless asked; nothing billable on GCP without asking first.
