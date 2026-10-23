# Answers written before the run

The harness invents the source data and writes the answer down first.
Tests then check against a known truth, not against what the pipeline produced.
A pipeline bug and an expectation bug can no longer cancel out.

```
harness/
├── generate.py   records + the manifest that says what must happen to them
├── bundle.py     packages .DAT + .CHS + .FLG (optionally PGP-encrypted)
└── load_db.py    builds the typed source tables for flink-jdbc (see spec/PIPELINE.md)
```

`manifest.json` is written **before** the run:

```json
{ "total_records": 405, "expected_written": 400, "expected_rejected": 5,
  "expected_by_reason": { "PARSE_BAD_DATE": 1, "SCHEMA_INVALID": 1, ... } }
```

The breakdown is seeded, not just the total. A record rejected for the wrong reason still balances.
So the acceptance suite checks each reason code individually.

## One record per failure mode

| Seeded defect | Must be rejected as |
|---|---|
| truncated line | `PARSE_SHORT_RECORD` |
| letters in a numeric field | `PARSE_BAD_NUMERIC` |
| impossible date | `PARSE_BAD_DATE` |
| status code not in the enum | `MAP_UNMAPPED_ENUM_VALUE` |
| violates the target schema | `SCHEMA_INVALID` |

## Same seed, same bytes

```bash
python -m harness.generate --accounts 400 --seed 99
```

The same seed gives the same records and manifest, byte for byte.
Used by `make run`, and by the beam-dataflow tests `tests/test_engine.py` and `tests/acceptance.py`.

## A new defect in three steps

1. Add the kind to `MALFORMED_KINDS`.
2. Implement the corruption in `_break()`.
3. Map it to its reason in `EXPECTED_REASON`.

`test_each_malformed_kind_produces_its_enumerated_reason` picks it up automatically.
