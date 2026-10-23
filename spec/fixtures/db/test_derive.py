"""The typed-source oracle (spec/fixtures/db/): derived, balanced, honest about source_file, and
strict — compare.py must fail on damage to every compared fact that is new here."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
SPEC = HERE.parents[1]
COMPARE = SPEC / "conformance" / "compare.py"
EXPECTED = HERE / "expected"
MANIFEST = HERE / "manifest.json"


def read(path: Path) -> tuple[list[str], list[list[str]]]:
    rows = list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"), newline=""), delimiter="\t"))
    return rows[0], rows[1:]


def write(path: Path, header: list[str], rows: list[list[str]]) -> None:
    buf = io.StringIO()
    w = csv.writer(buf, delimiter="\t", lineterminator="\n")
    w.writerow(header)
    w.writerows(rows)
    path.write_text(buf.getvalue(), encoding="utf-8")


def compare(dump: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-I", str(COMPARE), str(dump), "--expected", str(EXPECTED), "--manifest", str(MANIFEST)],
        capture_output=True, text=True,
    )


@pytest.fixture
def dump(tmp_path: Path) -> Path:
    d = tmp_path / "dump"
    shutil.copytree(EXPECTED, d)
    return d


def test_derive_reproduces_the_committed_oracle(tmp_path: Path):
    subprocess.run([sys.executable, "-I", str(HERE / "derive.py"), "--out", str(tmp_path)], check=True)
    for f in sorted(EXPECTED.glob("*.tsv")):
        assert (tmp_path / "expected" / f.name).read_bytes() == f.read_bytes(), f.name
    assert (tmp_path / "manifest.json").read_bytes() == MANIFEST.read_bytes()


def test_the_derived_oracle_balances():
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert m["src_read"] == m["expected_written"] + m["expected_rejected"] == 53
    i = m["intake_only"]
    assert i["src_read"] == i["extraction_written"] + i["rejected"] == 53
    assert m["content"] == {"row_count": 53, "content_digest": "31503124396361923406"}


def test_account_sql_is_the_pinned_one():
    m = json.loads(MANIFEST.read_text(encoding="utf-8"))
    assert hashlib.sha256((HERE / "account.sql").read_bytes()).hexdigest() == m["account_sql_sha256"]


def test_source_file_is_the_source_table():
    # compare.py skips _source_file / source_file by design (SKIP_COLS), so this fact is held here.
    h, rows = read(EXPECTED / "account_src.tsv")
    assert {r[h.index("_source_file")] for r in rows} == {"etlr_source.account"}
    h, rows = read(EXPECTED / "record_lineage.tsv")
    assert {r[h.index("source_file")] for r in rows if r[h.index("stage")] == "parse"} == {"etlr_source.account"}


def test_the_rows_the_database_refuses_are_absent():
    for name in ("account_src", "reject_log"):
        assert "ACC000900001" not in (EXPECTED / f"{name}.tsv").read_text(encoding="utf-8")
        assert "ACC000900002" not in (EXPECTED / f"{name}.tsv").read_text(encoding="utf-8")


def test_the_oracle_matches_itself(dump: Path):
    proc = compare(dump)
    assert proc.returncode == 0, proc.stdout + proc.stderr


def _damage_parse_reject(dump: Path, column: str, value: str) -> None:
    h, rows = read(dump / "reject_log.tsv")
    for r in rows:
        if r[h.index("stage")] == "parse":
            r[h.index(column)] = value
    write(dump / "reject_log.tsv", h, rows)


@pytest.mark.parametrize(
    "column,value",
    [("reason", "PARSE_BAD_COLUMN_COUNT"), ("detail", "CUST_ID"), ("raw_record", "ACC000900000|CUS0000000|RETL|CUR001|||")],
)
def test_damage_to_the_parse_reject_is_caught(dump: Path, column: str, value: str):
    _damage_parse_reject(dump, column, value)
    assert compare(dump).returncode == 1


def test_damage_to_the_content_name_is_caught(dump: Path):
    h, rows = read(dump / "record_lineage.tsv")
    for r in rows:
        if r[h.index("stage")] == "parse":
            r[h.index("source_key")] = "raw:0000000000000000"
    write(dump / "record_lineage.tsv", h, rows)
    assert compare(dump).returncode == 1


def test_a_ledger_with_the_file_lane_count_is_caught(dump: Path):
    h, rows = read(dump / "run_ledger.tsv")
    rows[0][h.index("src_read")] = "55"
    write(dump / "run_ledger.tsv", h, rows)
    assert compare(dump).returncode == 1
