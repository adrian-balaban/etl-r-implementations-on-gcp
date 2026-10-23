"""The comparator must fail on damage, not just pass on the oracle.

Each test copies the oracle to a temp dir, damages one thing, and expects compare.py to exit 1
with the matching check failing. Run: python -m pytest spec/conformance -q
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
SPEC = HERE.parent
EXPECTED = SPEC / "fixtures" / "expected"
COMPARE = HERE / "compare.py"
FLINK_GCP_DUMP = SPEC / "conformance" / "fixtures" / "real-flink-gcp-dump"


def run(dump: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-I", str(COMPARE), str(dump)], capture_output=True, text=True)


@pytest.fixture()
def dump(tmp_path: Path) -> Path:
    d = tmp_path / "dump"
    shutil.copytree(EXPECTED, d)
    return d


def edit(path: Path, old: str, new: str, count: int = 1) -> None:
    s = path.read_text()
    assert s.count(old) >= 1, f"{old!r} not in {path.name}"
    path.write_text(s.replace(old, new, count))


def test_oracle_matches_itself(dump):
    r = run(dump)
    assert r.returncode == 0, r.stdout
    assert "intake door tally" in r.stdout


@pytest.mark.skipif(not FLINK_GCP_DUMP.is_dir(), reason="real dump fixture not present")
def test_real_flink_gcp_dump_matches_and_is_whole_lane_shaped():
    r = run(FLINK_GCP_DUMP)
    assert r.returncode == 0, r.stdout
    assert "whole lane tally" in r.stdout


def test_a_changed_reject_detail_is_caught(dump):
    edit(dump / "reject_log.tsv", "OPEN_DATE='20191345'", "OPEN_DATE='20191346'")
    r = run(dump)
    assert r.returncode == 1
    assert "FAIL  parse/PARSE_BAD_DATE: detail" in r.stdout


def test_a_changed_reject_reason_is_caught(dump):
    # Right count, wrong reason: the failure mode to fear.
    edit(dump / "reject_log.tsv", "SCHEMA_INVALID", "MAP_TYPE_ERROR")
    r = run(dump)
    assert r.returncode == 1


def test_wrong_stage_for_the_same_reason_is_caught(dump):
    # map and schema are different stages (PIPELINE.md); merging them must not pass.
    edit(dump / "record_lineage.tsv", "\tschema\tSCHEMA_INVALID\t", "\tmap\tSCHEMA_INVALID\t")
    assert run(dump).returncode == 1


def test_a_missing_reject_row_is_caught(dump):
    p = dump / "reject_log.tsv"
    lines = p.read_text().splitlines(keepends=True)
    p.write_text("".join(lines[:-1]))
    r = run(dump)
    assert r.returncode == 1
    assert "rows in each" in r.stdout


def test_a_changed_target_document_is_caught(dump):
    edit(dump / "account_target.tsv", "DORMANT", "ACTIVE")
    r = run(dump)
    assert r.returncode == 1
    assert "account_target" in r.stdout


def test_a_dropped_enriched_row_is_caught(dump):
    p = dump / "account_enriched.tsv"
    lines = p.read_text().splitlines(keepends=True)
    p.write_text("".join(lines[:-1]))
    assert run(dump).returncode == 1


def test_an_unbalanced_ledger_is_caught(dump):
    edit(dump / "run_ledger.tsv", "\ttrue\t", "\tfalse\t")
    assert run(dump).returncode == 1


def test_a_ledger_tally_outside_the_manifest_is_caught(dump):
    edit(dump / "run_ledger.tsv", "\t55\t52\t3\t", "\t55\t51\t4\t")
    assert run(dump).returncode == 1


def test_a_missing_table_is_a_usage_error_not_a_pass(dump):
    (dump / "run_ledger.tsv").unlink()
    r = run(dump)
    assert r.returncode == 2
    assert "run_ledger.tsv" in r.stderr


def test_run_identity_alone_is_not_a_difference(dump):
    # Two different runs must be comparable: run_id differs by construction.
    for t in ("reject_log", "record_lineage", "account_target", "run_ledger"):
        edit(dump / f"{t}.tsv", "run-deepdive-clean-20260907", "run-some-other-run", count=10_000)
    assert run(dump).returncode == 0


def test_fixture_is_the_pinned_one():
    man = json.loads((SPEC / "fixtures/manifest.json").read_text())
    assert hashlib.sha256((SPEC / "fixtures/ACCOUNT.src").read_bytes()).hexdigest() == man["sha256"]


def test_generator_reproduces_the_fixture():
    """Beam's lane is fed by the generator, not the fixture file; they must be the same bytes."""
    sys.path.insert(0, str(SPEC))
    try:
        from harness.generate import generate
    finally:
        sys.path.pop(0)
    lines, _ = generate(50, 20260803)
    assert ("\n".join(lines) + "\n").encode() == (SPEC / "fixtures/ACCOUNT.src").read_bytes()


# --- single-pass profile ---------------------------------------------------------------------

def single_pass_dump(dump: Path) -> None:
    """Turn the oracle into what a single-pass implementation produces: no intermediate layers,
    and map/schema rejects carrying the original source line."""
    import csv
    for t in ("account_src", "account_curated", "account_enriched"):
        header = (dump / f"{t}.tsv").read_text().splitlines()[0]
        (dump / f"{t}.tsv").write_text(header + "\n")
    src = {ln.split("|", 1)[0]: ln for ln in (SPEC / "fixtures/ACCOUNT.src").read_text().splitlines()[1:]}
    rows = list(csv.reader((dump / "reject_log.tsv").open(newline=""), delimiter="\t"))
    hdr = rows[0]
    for r in rows[1:]:
        d = dict(zip(hdr, r))
        if d["stage"] != "parse":
            r[hdr.index("raw_record")] = src[d["source_key"]]
    with (dump / "reject_log.tsv").open("w", newline="") as f:
        csv.writer(f, delimiter="\t", lineterminator="\n").writerows(rows)


def run_sp(dump: Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-I", str(COMPARE), str(dump), "--profile", "single-pass"],
                          capture_output=True, text=True)


def test_single_pass_profile_accepts_a_single_pass_dump(dump):
    single_pass_dump(dump)
    r = run_sp(dump)
    assert r.returncode == 0, r.stdout
    assert "profile single-pass" in r.stdout and "NOT compared" in r.stdout


def test_default_profile_rejects_a_single_pass_dump(dump):
    # The staged profile must not be satisfiable by an implementation that skips the layers.
    single_pass_dump(dump)
    assert run(dump).returncode == 1


def test_single_pass_profile_still_checks_raw_record_against_the_source_line(dump):
    single_pass_dump(dump)
    edit(dump / "reject_log.tsv", "ACC000900003|CUS0000003|RETL|CUR001|RON|20001218|X|",
         "ACC000900003|CUS0000003|RETL|CUR001|RON|20001218|Y|")
    r = run_sp(dump)
    assert r.returncode == 1
    assert "raw_record is the original source line" in r.stdout


def test_single_pass_profile_still_compares_target_documents(dump):
    single_pass_dump(dump)
    edit(dump / "account_target.tsv", "DORMANT", "ACTIVE")
    assert run_sp(dump).returncode == 1
