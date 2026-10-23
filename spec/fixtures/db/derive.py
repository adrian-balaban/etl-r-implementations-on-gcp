"""Derive the typed-source oracle (spec/fixtures/db/) from the BigQuery oracle (spec/fixtures/expected/).

Loading ACCOUNT.src into a typed table changes only a few facts (spec/PIPELINE.md, "Source System as a
database"): two rows are refused by the database, the truncated row becomes PARSE_MISSING_FIELD on
CURRENCY with the canonical line as its raw record, the source is a table, and the ledger counts 53.
Everything else is copied unchanged. Re-run this; never hand-edit its output.

    python3 spec/fixtures/db/derive.py              # writes spec/fixtures/db/{expected/,manifest.json}
    python3 spec/fixtures/db/derive.py --out DIR    # writes elsewhere (test_derive.py uses this)
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parent
sys.path.insert(0, str(FIXTURES.parent / "harness"))

import load_db  # noqa: E402

SOURCE_FILE = load_db.SOURCE_TABLE
UNCHANGED = ("account_curated", "account_enriched", "account_target")
REQUIRED = ("ACCT_ID", "CUST_ID", "CLIENT_TYPE", "PRODUCT_CODE", "CURRENCY", "OPEN_DATE", "STATUS", "BALANCE")


def missing_field(row: tuple) -> str | None:
    """The typed parse door: the first required field (TDS order) that is NULL or blank after TRIM."""
    for name, value in zip(REQUIRED, row):
        if value is None or (isinstance(value, str) and value.strip() == ""):
            return name
    return None


def read(path: Path) -> tuple[list[str], list[list[str]]]:
    rows = list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"), newline=""), delimiter="\t"))
    return rows[0], rows[1:]


def write(path: Path, header: list[str], rows: list[list[str]]) -> None:
    buf = io.StringIO()
    w = csv.writer(buf, delimiter="\t", lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    path.write_text(buf.getvalue(), encoding="utf-8")


def derive(out: Path) -> None:
    src = FIXTURES / "expected"
    loaded = load_db.load((FIXTURES / "ACCOUNT.src").read_text(encoding="utf-8").splitlines())
    refused = {line.split("|", 1)[0]: why for line, why in loaded.refused}
    rejected = [r for r in loaded.rows if missing_field(r) is not None]
    assert len(rejected) == 1, f"expected exactly one typed parse reject, got {len(rejected)}"
    line = load_db.canonical_line(rejected[0])
    field = missing_field(rejected[0])

    exp = out / "expected"
    exp.mkdir(parents=True, exist_ok=True)
    for name in UNCHANGED:
        shutil.copyfile(src / f"{name}.tsv", exp / f"{name}.tsv")

    h, rows = read(src / "account_src.tsv")
    for r in rows:
        r[h.index("_source_file")] = SOURCE_FILE
    write(exp / "account_src.tsv", h, rows)

    h, rows = read(src / "reject_log.tsv")
    col = {c: i for i, c in enumerate(h)}
    parse_key = {}  # (reason, detail) -> acct_id of the file-lane parse reject
    kept = []
    for r in rows:
        if r[col["stage"]] != "parse":
            kept.append(r)
            continue
        acct = r[col["raw_record"]].split("|", 1)[0]
        parse_key[(r[col["reason"]], r[col["detail"]])] = acct
        if acct in refused:
            continue
        r[col["reason"]], r[col["detail"]], r[col["raw_record"]] = "PARSE_MISSING_FIELD", field, line
        kept.append(r)
    write(exp / "reject_log.tsv", h, kept)
    by_stage = [{"stage": r[col["stage"]], "reason": r[col["reason"]], "detail": r[col["detail"]]} for r in kept]

    h, rows = read(src / "record_lineage.tsv")
    col = {c: i for i, c in enumerate(h)}
    kept = []
    for r in rows:
        if r[col["stage"]] == "parse":
            acct = parse_key[(r[col["reason"]], r[col["detail"]])]
            if acct in refused:
                continue
            r[col["source_key"]] = load_db.content_name(line)
            r[col["reason"]], r[col["detail"]], r[col["source_file"]] = "PARSE_MISSING_FIELD", field, SOURCE_FILE
        kept.append(r)
    write(exp / "record_lineage.tsv", h, kept)

    src_read = len(loaded.rows)
    written = len(read(src / "account_target.tsv")[1])
    h, rows = read(src / "run_ledger.tsv")
    col = {c: i for i, c in enumerate(h)}
    for r in rows:  # the intake door's tally, the same shape the BigQuery oracle holds
        r[col["src_read"]], r[col["extraction_written"]], r[col["rejected"]] = str(src_read), str(src_read - 1), "1"
    write(exp / "run_ledger.tsv", h, rows)

    manifest = {
        "_comment": "Typed-source oracle, derived by spec/fixtures/db/derive.py from spec/fixtures/expected/ "
                    "(the BigQuery oracle of gcp-deepdive-20260907). NOT independent evidence: every value is "
                    "inherited except the facts the typed load changes (spec/PIPELINE.md, 'Source System as a database').",
        "source_table": SOURCE_FILE,
        "account_sql_sha256": hashlib.sha256((HERE / "account.sql").read_bytes()).hexdigest(),
        "src_read": src_read,
        "expected_written": written,
        "expected_rejected": src_read - written,
        "expected_by_stage_reason": by_stage,
        "intake_only": {"src_read": src_read, "extraction_written": src_read - 1, "rejected": 1},
        "refused_by_database": [{"acct_id": k, "reason": v} for k, v in sorted(refused.items())],
        "content": {"row_count": src_read, "content_digest": str(load_db.digest(loaded.rows))},
        "known_keys": {
            "canonical_line_of_truncated_row": line,
            "content_name_of_truncated_row": load_db.content_name(line),
            "part_of_truncated_row": load_db.part(line),
        },
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--out", type=Path, default=HERE)
    derive(ap.parse_args().out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
