# K02 answers: The contract as a shared library

## Task 1: The ADL with `DEFINE LIBRARY Contracts AS spec/contracts`

```
DESCRIPTION Define contract library shared between implementations
CATEGORY Engineering Practices
DEFINE LIBRARY Contracts AS spec/contracts
     DEFINE CONST ARTEFACT_DAT ".DAT"
     DEFINE CONST ARTEFACT_CHS ".CHS"
     DEFINE CONST ARTEFACT_DAT_NAME "{record}.{sequence}.DAT"
     DEFINE CONST ARTEFACT_CHS_NAME "{record}.CHS"
     DEFINE CONST RUN_ID "_run_id"
     DEFINE CONST ACCOUNT_KEY "_account_key"
     DEFINE CONST CONTENT_NAME "raw:" + sha256(raw line)[:16]
     DEFINE CONST SOURCE_KEY ""
     ASSERT(artefact names derive from Contracts)
     ASSERT(byte-identic.jar copy of artefacts.json)
```
This is the centralized contract that all implementations read at runtime (Python) or at build time (Java/Maven). `artefacts.json` holds the standardized names for `.DAT`, `.CHS`, `.FLG`, `.RPT`, `.ERR` and the shared columns `_run_id`, `_account_key`. The "Do not hand-edit spec/fixtures/ACCOUNT.src" prohibition becomes an ADL assertion: `ASSERT(ACCOUNT.src unchanged since sha256 pinned in fixtures/manifest.json)`.

## Task 2: The fitness-function plan

**The passing case:**
- The Java build runs `mvn dependency:copy-artefacts`, which copies `artefacts.json` out of `spec/contracts/` onto the classpath
- The test checks the copy's SHA-256 checksum against the source
- `make verify-project2` passes with zero changes in `pipelines/` or `apps/`

**The failing case:**
- The agent edits `spec/fixtures/ACCOUNT.src` directly → the checksums differ → `make spec-test` fails
- `artefacts.json` changed without consistently updating the mappings → `make conformance` fails on `SCHEMA_INVALID`
- The Python test reads `artefacts.json` from the wrong directory → wrong file names → the `load` step fails with `PARSE_MISSING_FIELD`

Interpolated fitness function (from the ADL): `DEFINE CONST MAX_SHARED_CHANGES = 2` + `ASSERT(shared changes no more than MAX_SHARED_CHANGES times per month)`, runnable in CI.

## Task 3: The "IS DEPENDENT ON" trap

**The agent's wrong result:**
- The agent generates an ArchUnit rule: `ArchRule senderDependsOnModel = classes().that().resideInAPackage("..testing.question_service.sender..").should().dependOnClassesThat().resideInAPackage("..testing.test_model..");`
- This passes because **IS DEPENDENT ON** is an **existential claim**: "at least one class uses B", not "every class".
- If a single utility helper class uses test_model, the check passes even though 99% of the classes have no connection to the model.
- In reality, `Question Sender` depends on `Test Model` only through the specific class `Test Loader`, not through any class in the package.

**Correctness:** `IS DEPENDENT ON` generates "at least one class uses", not "every class". For a per-class check, the architect should run a custom per-class rule, or use `HAS NO DEPENDENCY ON` to list the interdependencies explicitly.

## Task 4: Where the fitness function runs

- **CI/CD trigger:** on every build/PR it runs `make spec-test` and checks `artefacts.json`'s byte-identity. On failure, the build is blocked.
- **MCP on-demand:** an agent or the architect can run the MCP tool `get_artefact_config()` to compare the current `artefacts.json` tree with the canonical one kept in the governance context.
- **Interactively:** during a kata/workshop, the agent receives the ADL as context and checks `ASSERT(artefact names derive from Contracts)` manually against the real tree of build-generated files.

**Recommendation:** CI/CD-triggered as the release gate, plus MCP tools for on-demand checks (as `make-the-grade-pages-*/answers.md` recommends for the other tasks).