# The input, annotated

`00-INPUT-ACCOUNT.src` is the oracle's input: a header and **55 records** (56 lines), sha256
`86f76332f08e7c8c0a0a78a4366fb124151f3467fbb3a68a556fd6461604426c`. Fifty are healthy. Five carry one seeded defect each:

| Line | Record | The defect | Must be rejected as |
|---|---|---|---|
| 11 | `ACC000900000` | a truncated line: 4 columns, not 8 | `PARSE_BAD_COLUMN_COUNT` at parse |
| 27 | `acc-bad!0001` | an `accountId` outside `^[A-Z0-9]+$` | `SCHEMA_INVALID` at schema |
| 29 | `ACC000900001` | `BALANCE` is `+0000000ABCD.56`, not a number | `PARSE_BAD_NUMERIC` at parse |
| 33 | `ACC000900003` | `STATUS` is `X`, not `A`, `C` or `D` | `MAP_UNMAPPED_ENUM_VALUE` at map |
| 56 | `ACC000900002` | `OPEN_DATE` is `20191345`, not a date | `PARSE_BAD_DATE` at parse |

The file is generated, not hand-edited: `python -m harness.generate --accounts 50`. It is pinned byte for byte by a test.
