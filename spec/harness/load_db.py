"""Load TDS account records into the typed Source System table etlr_source.account.

Prints SQL for psql: the schema, a full-snapshot reload of the table, and the extract_control row
that vouches for it (row count + content digest). Records a typed table cannot hold are refused and
listed on stderr, never rounded or truncated. See spec/PIPELINE.md, "Source System as a database".

    python3 spec/harness/load_db.py spec/fixtures/ACCOUNT.src --run-id fixture \
        --ready-at 2026-09-07T00:00:00Z > account.sql
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import re
import sys
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

SOURCE_TABLE = "etlr_source.account"
FIELDS = ("acct_id", "cust_id", "client_type", "product_code", "currency", "open_date", "status", "balance")
LENGTHS = {"acct_id": 32, "cust_id": 32, "client_type": 8, "product_code": 16, "currency": 3, "status": 2}
MAX_BALANCE = Decimal(10) ** 16  # NUMERIC(18,2) holds 16 integer digits
RUN_ID = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")  # mig.common.RunId

DDL = """CREATE SCHEMA IF NOT EXISTS etlr_source;
CREATE TABLE IF NOT EXISTS etlr_source.account (
  acct_id      VARCHAR(32) PRIMARY KEY,
  cust_id      VARCHAR(32),
  client_type  VARCHAR(8),
  product_code VARCHAR(16),
  currency     VARCHAR(3),
  open_date    DATE,
  status       VARCHAR(2),
  balance      NUMERIC(18,2));
CREATE TABLE IF NOT EXISTS etlr_source.extract_control (
  run_id         TEXT PRIMARY KEY,
  table_name     TEXT        NOT NULL,
  ready_at       TIMESTAMPTZ NOT NULL,
  row_count      BIGINT      NOT NULL,
  content_digest NUMERIC     NOT NULL);"""


class Refused(Exception):
    """A value the typed table cannot hold; the message is the refusal reason."""


@dataclass
class Loaded:
    rows: list[tuple] = field(default_factory=list)
    refused: list[tuple[str, str]] = field(default_factory=list)  # (source line, reason)


def _date(raw: str) -> datetime.date | None:
    if raw == "":
        return None
    if len(raw) != 8 or not raw.isdigit():
        raise Refused("date")
    try:
        return datetime.datetime.strptime(raw, "%Y%m%d").date()
    except ValueError:
        raise Refused("date") from None


def _balance(raw: str) -> Decimal | None:
    if raw == "":
        return None
    try:
        value = Decimal(raw)
    except InvalidOperation:
        raise Refused("numeric") from None
    if not value.is_finite():
        raise Refused("numeric")
    if -value.as_tuple().exponent > 2:
        raise Refused("scale")
    value = value.quantize(Decimal("0.01"))
    if value == 0:
        value = Decimal("0.00")  # Postgres NUMERIC has no negative zero
    if abs(value) >= MAX_BALANCE:
        raise Refused("range")
    return value


def _row(line: str) -> tuple:
    cols = line.split("|")
    if len(cols) > len(FIELDS):
        raise Refused("columns")
    cols = cols + [None] * (len(FIELDS) - len(cols))  # missing trailing columns become NULL
    for name, value in zip(FIELDS, cols):
        if name in LENGTHS and value is not None and len(value) > LENGTHS[name]:
            raise Refused(f"length:{name}")
    date = _date(cols[5]) if cols[5] is not None else None
    balance = _balance(cols[7]) if cols[7] is not None else None
    return (cols[0], cols[1], cols[2], cols[3], cols[4], date, cols[6], balance)


def load(lines: list[str]) -> Loaded:
    out = Loaded()
    seen: set[str] = set()
    for line in lines:
        if not line.strip() or line.split("|", 1)[0] == "ACCT_ID":
            continue
        try:
            row = _row(line)
        except Refused as why:
            out.refused.append((line, str(why)))
            continue
        key = row[0].strip()
        if key in seen:
            out.refused.append((line, "duplicate key"))
            continue
        seen.add(key)
        out.rows.append(row)
    return out


def _text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, datetime.date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return format(value, "f")
    return str(value)


def canonical_line(row: tuple) -> str:
    """Fields in TDS order, strings untrimmed, DATE as YYYY-MM-DD, NUMERIC with 2 decimals, NULL as ''."""
    return "|".join(_text(v) for v in row)


def _sha256(line: str) -> str:
    return hashlib.sha256(line.encode("utf-8")).hexdigest()


def part(line: str) -> int:
    return int(_sha256(line)[:15], 16)


def content_name(line: str) -> str:
    return "raw:" + _sha256(line)[:16]


def digest_lines(lines) -> int:
    return sum(part(line) for line in lines)


def digest(rows: list[tuple]) -> int:
    return digest_lines(canonical_line(r) for r in rows)


def _literal(value: object) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, Decimal):
        return format(value, "f")
    return "'" + _text(value).replace("'", "''") + "'"


def to_sql(loaded: Loaded, run_id: str, ready_at: str) -> str:
    out = ["-- generated by spec/harness/load_db.py; do not edit", "BEGIN;", DDL, f"TRUNCATE {SOURCE_TABLE};"]
    for row in loaded.rows:
        out.append(f"INSERT INTO {SOURCE_TABLE} VALUES ({', '.join(_literal(v) for v in row)});")
    out.append(
        "INSERT INTO etlr_source.extract_control (run_id, table_name, ready_at, row_count, content_digest)\n"
        f"VALUES ({_literal(run_id)}, '{SOURCE_TABLE}', {_literal(ready_at)}, {len(loaded.rows)}, "
        f"{digest(loaded.rows)})\n"
        "ON CONFLICT (run_id) DO UPDATE SET table_name = EXCLUDED.table_name, ready_at = EXCLUDED.ready_at,\n"
        "  row_count = EXCLUDED.row_count, content_digest = EXCLUDED.content_digest;"
    )
    out.append("COMMIT;")
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description="Load TDS account records into etlr_source.account (prints SQL)")
    ap.add_argument("source", type=Path, help="a TDS ACCOUNT file (pipe-delimited, header optional)")
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--ready-at", default=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"))
    args = ap.parse_args()
    if not RUN_ID.match(args.run_id) or len(args.run_id) > 49:
        print(f"invalid run id {args.run_id!r} (must match {RUN_ID.pattern}, at most 49 chars)", file=sys.stderr)
        return 2
    loaded = load(args.source.read_text(encoding="utf-8").splitlines())
    for line, why in loaded.refused:
        print(f"refused ({why}): {line}", file=sys.stderr)
    sys.stdout.write(to_sql(loaded, args.run_id, args.ready_at))
    return 0


if __name__ == "__main__":
    sys.exit(main())
