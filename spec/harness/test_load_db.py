"""load_db.py: the typed Source System load, the canonical line and the content digest.

Pinned values come from docs/superpowers/specs/2026-10-20-flink-jdbc-design.md ("Pinned values"),
verified in Python and in real Postgres 16.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import load_db  # noqa: E402

FIXTURE = HERE.parent / "fixtures" / "ACCOUNT.src"
TRUNCATED = "ACC000900000|CUS0000000|RETL|CUR001||||"


def fixture() -> load_db.Loaded:
    return load_db.load(FIXTURE.read_text(encoding="utf-8").splitlines())


def test_fixture_loads_53_rows_and_refuses_the_bad_date_and_the_bad_numeric():
    loaded = fixture()
    assert len(loaded.rows) == 53
    assert sorted((line.split("|")[0], why) for line, why in loaded.refused) == [
        ("ACC000900001", "numeric"),
        ("ACC000900002", "date"),
    ]


def test_fixture_digest_is_the_pinned_one():
    assert load_db.digest(fixture().rows) == 31503124396361923406


def test_the_truncated_row_renders_to_the_pinned_canonical_line():
    lines = [load_db.canonical_line(r) for r in fixture().rows]
    assert TRUNCATED in lines
    assert load_db.content_name(TRUNCATED) == "raw:bf47701944c6abec"
    assert load_db.part(TRUNCATED) == 861444277401316030


def test_padding_is_kept_and_types_render_canonically():
    row = load_db.load(["ACC1|C1|SME |P|EUR|20080920|D|+00000300299.47"]).rows[0]
    assert load_db.canonical_line(row) == "ACC1|C1|SME |P|EUR|2008-09-20|D|300299.47"


def test_the_digest_ignores_order_but_counts_duplicates():
    a, b = "x|1", "y|2"
    assert load_db.digest_lines([a, b]) == load_db.digest_lines([b, a])
    assert load_db.digest_lines([a, a]) == 2 * load_db.part(a) != 0


def test_the_header_is_skipped():
    header = "ACCT_ID|CUST_ID|CLIENT_TYPE|PRODUCT_CODE|CURRENCY|OPEN_DATE|STATUS|BALANCE"
    assert len(load_db.load([header, "ACC1|C|RETL|P|EUR|20080920|D|1.00"]).rows) == 1


def test_an_empty_date_becomes_null_not_a_refusal():
    row = load_db.load(["ACC1|C|RETL|P|EUR||D|1.00"]).rows[0]
    assert row[5] is None
    assert load_db.canonical_line(row) == "ACC1|C|RETL|P|EUR||D|1.00"


def test_a_third_decimal_is_refused_not_rounded():
    assert load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|1.005"]).refused[0][1] == "scale"


def test_a_numeric_out_of_range_is_refused():
    assert load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|+12345678901234567.00"]).refused[0][1] == "range"


def test_a_value_longer_than_its_column_is_refused():
    assert load_db.load(["ACC1|C|RETL|P|EURO|20080920|D|1.00"]).refused[0][1] == "length:currency"


def test_extra_columns_are_refused():
    assert load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|1.00|extra"]).refused[0][1] == "columns"


def test_a_key_that_repeats_after_trim_is_refused():
    loaded = load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|1.00", "ACC1 |C|RETL|P|EUR|20080920|D|2.00"])
    assert len(loaded.rows) == 1
    assert loaded.refused[0][1] == "duplicate key"


def test_negative_zero_renders_as_zero_like_postgres():
    row = load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|-0.00"]).rows[0]
    assert load_db.canonical_line(row).endswith("|0.00")


def test_sql_carries_the_rows_and_the_control_row():
    sql = load_db.to_sql(load_db.load(["ACC1|C|RETL|P|EUR|20080920|D|1.00"]), "run-x", "2026-09-07T00:00:00Z")
    assert "TRUNCATE etlr_source.account;" in sql
    assert "INSERT INTO etlr_source.account VALUES ('ACC1', 'C', 'RETL', 'P', 'EUR', '2008-09-20', 'D', 1.00);" in sql
    assert "VALUES ('run-x', 'etlr_source.account', '2026-09-07T00:00:00Z', 1, " in sql


def test_a_bad_run_id_is_rejected():
    proc = subprocess.run(
        [sys.executable, "-I", str(HERE / "load_db.py"), str(FIXTURE), "--run-id", "Bad Id"],
        capture_output=True, text=True,
    )
    assert proc.returncode == 2

def test_emit_sql_reproduces_the_committed_fixture_sql():
    out = subprocess.run(
        [sys.executable, "-I", str(HERE / "load_db.py"), str(FIXTURE), "--run-id", "fixture",
         "--ready-at", "2026-09-07T00:00:00Z"],
        capture_output=True, text=True, check=True,
    ).stdout
    assert out == (HERE.parent / "fixtures" / "db" / "account.sql").read_text(encoding="utf-8")
