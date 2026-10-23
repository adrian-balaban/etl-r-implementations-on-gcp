# New project, zero code changes

A different migration means adding files here, and nowhere else. **Extend, never rewrite.**
`make verify-project2` runs a deliberately different project end to end.
It fails if any tracked implementation file changed.

All three implementations read this folder through a `contracts/` symlink.
Paths such as `pipelines/`, `apps/` and `../dataform` are relative to `implementations/beam-dataflow/`.

```
contracts/
├── tds/          record layouts: tds-{src,target}-project{1,2}.def
├── mappings/     source record → target document: mapping-project{1,2}.yaml
├── schemas/      JSON Schema every output document must satisfy
├── reference/    lookup data joined during enrichment (branches)
└── artefacts.json  artefact naming + shared columns, for every project
```

**`artefacts.json` is the contract between the languages.** It is not per project.
- Python reads it at runtime (`pipelines/common/artefacts.py`).
- Java loads it from the classpath (`Artefacts.java`). Maven copies it at build time.
- So `.FLG`, `.CHS`, `.DAT` names and `_run_id`, `_account_key` are declared once.
- Tests assert that the Python names derive from it, and that the jar's copy is byte-identical.

## The mapping is the transformation

```yaml
  - target: status          # field in the Target System document
    from: STATUS            # field in the Source System record
    transform: map_enum
    values: { A: ACTIVE, C: CLOSED, D: DORMANT }
```

`STATUS=X` is **rejected** as `MAP_UNMAPPED_ENUM_VALUE`. Never dropped, never guessed.

## Pipe-delimited, header skipped

Every source `.DAT` is pipe-delimited CSV with a header row (`layout csv delimiter=| header=true`).
The header is skipped, not parsed, in three places that could miscount it:
- `RecordParser.is_header` in `pipelines/common/tds.py`.
- `ExtractorApp.java`, before `.RPT documentsRead` is written.
- `ReadBundleFn`, before a line reaches `src_read`.

**Load raw, normalize in the transform.**
Parse keeps values exactly as they arrive. Every normalization is an explicit `transform:`.
`TRANSFORMS` in `pipelines/common/mapping.py` holds `trim`, `upper`, `to_decimal`, `map_enum`,
`strip_trailing_spaces` and `strip_leading_zeros`. No current contract needs the last two.

## project2 is the proof

| | project1 | project2 |
|---|---|---|
| Record | ACCOUNT | DEPOSIT |
| Fields carried | PRODUCT_CODE among others | PRODUCT_CODE dropped |
| Target | TARGET_SYSTEM_ACCOUNT | TARGET_SYSTEM_DEPOSIT |
| Transform | — | one project1 never uses |

The load-bearing check is `git diff --quiet -- pipelines apps`.

The folder hash cannot fail. It compares the tree with itself.
It only trips if a test writes into the tree.

One caveat: a new project adds one `.sqlx` in `../dataform`.
Adding a model is allowed. Editing an existing one is not.

## Five files make a project

1. `tds/tds-src-project3.def`
2. `tds/tds-target-project3.def`
3. `mappings/mapping-project3.yaml`
4. `schemas/target-<thing>.schema.json`
5. One `.sqlx` in `../dataform/definitions/`

Run with `MAPPING=contracts/mappings/mapping-project3.yaml`.

If you had to edit `pipelines/` or `apps/`, the implementation lacked a capability. Add it generically.
