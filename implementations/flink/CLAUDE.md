# CLAUDE.md

Guidance for Claude Code in this repo. Read `spec/PIPELINE.md` and the diagrams in
`docs/diagrams/flink/` before making changes here.

## Build

Maven multi-module, Java 17. `pom.xml` is the single source of dependency versions.

```
mvn test                                      # all modules (podman ITs skip cleanly without podman)
mvn -pl <module> -am test                     # one module + its dependencies
```

**Important:** `pom.xml`'s `maven-surefire-plugin` configuration explicitly includes
`**/*IT.java` — Surefire's own default does NOT match that suffix (that's Failsafe's
convention), so every integration test in this repo (`IntakeJobIT`, `CurateEnrichJobIT`,
`ProduceJobIT`, `ThreeWayParityIT`, `SettleLoopIT`) was silently skipped by plain `mvn
test` before this override was added. Do not remove it, and if you add a new `*IT.java`
class, confirm `mvn test` actually prints `Running mig....SomeNewIT` — a green build with
zero output for it means it did not run.

Podman-backed integration tests start real containers (Postgres, and for some,
Redpanda + a `target-system-mock` subprocess) and skip cleanly
(`Assumptions.assumeTrue`) when `podman` is not on PATH, rather than failing the build.

Do not quote a test count in this file — run the suite if you need the number.

`make up && make cluster-submit` runs the jobs on the REAL composed Flink cluster rather
than the embedded mini cluster every IT uses. That distinction is not cosmetic: it is what
hid the CurateEnrichJob double-submission bug through an entire GCP deploy cycle. `ApplicationModeGuardIT`
now arms the same guard application mode arms, so `mvn test` catches that bug class too.

## Conventions

- Never commit or push unless explicitly asked.
- `contracts/`, `fixtures/`, `harness/generate.py` and `harness/bundle.py` are **symlinks into
  `../../spec/`** (the monorepo's single definition, shared with `beam-dataflow`) — edit them there,
  never here. Do not hand-edit `fixtures/ACCOUNT.src` (its sha256 is pinned in
  `fixtures/manifest.json` and asserted against) or the `contracts/` files without checking whether
  `fixtures/expected/*.tsv` still applies. `make conformance` (and `make -C ../.. conformance-all`)
  checks this lane against that oracle.
- The five parse-key corrections are load-bearing, not stylistic — before changing
  `Doors.java`, `Tds.java`, `Mapping.java`, `RecordRouter.java`, or any `.sql.template`,
  rerun `ParityTest` + `ThreeWayParityIT`.
- `common`'s `Doors`/`Keys`/`Tds`/`Mapping`/`RecordRouter`/`TargetSchemaValidator` are the
  one ported implementation of the two-door engine — both Flink variants call into it or
  reproduce it in SQL; do not fork the logic into a variant module.
- `target-system-mock` is deliberately Kafka-only (no HTTP `POST /v1/accounts`) — this
  lane has no `load` app, unlike `beam-dataflow` (here the load stage is the Kafka sink inside `produce`). See its class javadoc before
  adding an HTTP intake path back.
