#!/usr/bin/env python3
"""Conformance check: do an implementation's table dumps match the oracle, row for row?

    python3 spec/conformance/compare.py <dump-dir> [--expected spec/fixtures/expected]

<dump-dir> holds one TSV per table, named <table>.tsv, header row first:
account_src, account_curated, account_enriched, account_target, reject_log, record_lineage,
run_ledger. Both quoting styles are read: the oracle's raw BigQuery TSV and psql's
`COPY ... CSV DELIMITER E'\\t'` (quoted fields, doubled quotes). Exit 0 only if every
comparison passes.

Not a count check: "5 rejected" matching without the same breakdown is the failure to fear.
It compares stage/reason/detail/source_key/raw_record for reject_log, the lineage keys, and
each TARGET document as a parsed JSON tree.

Generalised from  in implementations/flink, which
passed against a real GCP run. The deviations below are recorded rather than hidden, and the
report prints each one it applied:

  - run identity differs by construction (a different run): run_id / _run_id / runId excluded;
  - ingest metadata is wall-clock or implementation-local: _ingested_at, _batch_id, _source_file,
    created_at excluded; record_lineage.source_file excluded (it names the dataset/schema);
  - column NAMES are compared case-insensitively: the oracle keeps the uppercase TDS field
    names (BigQuery), Postgres lowercases them;
  - empty string, NULL, \\N and "" are the same thing across a TSV dump.

Profiles (--profile):
  staged       (default) the implementation materialises every layer: all seven tables are compared.
  single-pass  the implementation routes records in one pass and has no intermediate layers (the
               Flink DataStream variant). account_src / account_curated / account_enriched are NOT
               compared, and for map/schema rejects raw_record is held to the stricter rule that it
               equals the ORIGINAL source line from fixtures/ACCOUNT.src (the oracle's value there is
               the enriched row's JSON, which a single-pass job never builds). Everything else - the
               rejects, the lineage, every TARGET document, the ledger - is compared in full.
The report states which profile ran, so a pass is never read as more than it covers.

run_ledger is the one table whose *shape* differs between implementations, and it is reported
as such: the oracle holds the intake door's tally (src_read=55, extraction_written=52,
rejected=3), while the Flink lane ends with the whole-lane tally (50 / 5). Either is accepted
when it matches the manifest AND balances; the report says which one it saw.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_EXPECTED = HERE.parent / "fixtures" / "expected"
MANIFEST = HERE.parent / "fixtures" / "manifest.json"
SOURCE_FIXTURE = HERE.parent / "fixtures" / "ACCOUNT.src"

NULLS = {"", "\\N", "NULL", "null"}
RUN_COLS = {"run_id", "_run_id"}
SKIP_COLS = RUN_COLS | {"_ingested_at", "_batch_id", "_source_file", "created_at", "batch_id"}
TREE_DROP = {"run_id", "runId"}

TABLES = (
    "account_src", "account_curated", "account_enriched", "account_target",
    "reject_log", "record_lineage", "run_ledger",
)


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.checks = 0

    def check(self, label: str, ok: bool, detail: str = "") -> None:
        self.checks += 1
        print(f"  {'PASS' if ok else 'FAIL'}  {label}" + (f"  - {detail}" if detail and not ok else ""))
        if not ok:
            self.failures.append(label)


def norm(v: object) -> str:
    s = "" if v is None else str(v)
    return "" if s in NULLS or s == '""' else s


def read_table(path: Path) -> list[dict[str, str]]:
    """Rows as dicts keyed by lowercased column name."""
    text = path.read_text(encoding="utf-8")
    reader = csv.reader(io.StringIO(text, newline=""), delimiter="\t")
    try:
        header = [h.strip().lower() for h in next(reader)]
    except StopIteration:
        return []
    rows = []
    for rec in reader:
        if not rec or all(not c.strip() for c in rec):
            continue
        rows.append(dict(zip(header, rec)))
    return rows


def tree(s: str) -> object:
    """Parse JSON for comparison; run identity dropped so two different runs can match."""
    d = json.loads(s)
    if isinstance(d, dict):
        d = {k: v for k, v in d.items() if k not in TREE_DROP}
        mig = d.get("migration")
        if isinstance(mig, dict):
            d["migration"] = {k: v for k, v in mig.items() if k not in TREE_DROP}
    return d


def same_value(col: str, x: str, y: str) -> bool:
    x, y = norm(x), norm(y)
    if x == y:
        return True
    if x.startswith(("{", "[")) and y.startswith(("{", "[")):
        try:
            return tree(x) == tree(y)
        except ValueError:
            return False
    return False


def compare_keyed(rep: Report, name: str, exp: list[dict], act: list[dict], key: str, label: str) -> None:
    print(f"{name}  ({label})")
    e = {r[key]: r for r in exp}
    a = {norm(r.get(key)): r for r in act}
    rep.check(f"{len(e)} rows in the oracle, same {key}s",
              set(e) == set(a) and len(e) == len(exp),
              f"only-expected={sorted(set(e) - set(a))[:3]} only-actual={sorted(set(a) - set(e))[:3]}")
    diffs = []
    for k in sorted(set(e) & set(a)):
        for col, ev in e[k].items():
            if col in SKIP_COLS or col not in a[k]:
                continue
            if not same_value(col, ev, a[k][col]):
                diffs.append((k, col, ev[:60], a[k][col][:60]))
    rep.check("all shared columns identical", not diffs, f"{len(diffs)} differ, first: {diffs[:2]}")


def source_lines() -> dict[str, str]:
    """ACCT_ID -> the original source line, from the immutable fixture (header skipped)."""
    lines = SOURCE_FIXTURE.read_text(encoding="utf-8").splitlines()[1:]
    return {ln.split("|", 1)[0]: ln for ln in lines if ln.strip()}


def compare_rejects(rep: Report, exp: list[dict], act: list[dict], single_pass: bool = False) -> None:
    print("reject_log  (stage, reason, detail, source_key, raw_record)")
    sk = lambda r: (r["stage"], r["reason"], norm(r.get("source_key")))
    e, a = sorted(exp, key=sk), sorted(act, key=sk)
    rep.check(f"{len(e)} rows in each", len(e) == len(a), f"expected {len(e)}, got {len(a)}")
    for x, y in zip(e, a):
        tag = f"{x['stage']}/{x['reason']}"
        for col in ("stage", "reason", "detail", "source_key"):
            rep.check(f"{tag}: {col}", norm(x[col]) == norm(y.get(col)), f"{x[col]!r} != {y.get(col)!r}")
        if single_pass and x["stage"] != "parse":
            src = source_lines().get(norm(x["source_key"]))
            rep.check(f"{tag}: raw_record is the original source line (single-pass)",
                      src is not None and y.get("raw_record", "") == src,
                      f"{y.get('raw_record', '')[:70]!r} != {src!r}")
        else:
            rep.check(f"{tag}: raw_record", same_value("raw_record", x["raw_record"], y.get("raw_record", "")),
                      "differs")


def compare_lineage(rep: Report, exp: list[dict], act: list[dict]) -> None:
    print("record_lineage  (source_key, account_key, door, stage, reason, detail; source_file excluded)")
    sk = lambda r: (r["stage"], r["reason"], norm(r.get("source_key")))
    e, a = sorted(exp, key=sk), sorted(act, key=sk)
    rep.check(f"{len(e)} rows in each", len(e) == len(a), f"expected {len(e)}, got {len(a)}")
    for x, y in zip(e, a):
        for col in ("source_key", "account_key", "door", "stage", "reason", "detail"):
            rep.check(f"{x['stage']}/{x['reason']}: {col}", norm(x[col]) == norm(y.get(col)),
                      f"{x[col]!r} != {y.get(col)!r}")


def compare_ledger(rep: Report, act: list[dict], manifest: Path = MANIFEST) -> None:
    print("run_ledger")
    man = json.loads(manifest.read_text())
    intake = (str(man["intake_only"]["src_read"]), str(man["intake_only"]["extraction_written"]),
              str(man["intake_only"]["rejected"]))
    full = (str(man["src_read"]), str(man["expected_written"]), str(man["expected_rejected"]))
    rep.check("exactly one ledger row for the run", len(act) == 1, f"got {len(act)}")
    if not act:
        return
    row = act[0]
    got = (norm(row.get("src_read")), norm(row.get("extraction_written")), norm(row.get("rejected")))
    shape = "intake door" if got == intake else "whole lane" if got == full else None
    rep.check(f"tally matches the manifest (intake {intake} or whole lane {full})", shape is not None,
              f"got {got}")
    if shape:
        print(f"        ledger shape: {shape} tally {got}")
    balanced = norm(row.get("balanced")).lower() in {"t", "true", "1"}
    rep.check("balanced, and src_read == written + rejected",
              balanced and int(got[0]) == int(got[1]) + int(got[2]) if all(got) else False, str(row))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dump_dir", type=Path)
    ap.add_argument("--expected", type=Path, default=DEFAULT_EXPECTED)
    ap.add_argument("--manifest", type=Path, default=MANIFEST,
                    help="the oracle's manifest (default: spec/fixtures/manifest.json)")
    ap.add_argument("--profile", choices=("staged", "single-pass"), default="staged",
                    help="single-pass: no intermediate layers (see the module docstring)")
    args = ap.parse_args()

    missing = [t for t in TABLES if not (args.dump_dir / f"{t}.tsv").is_file()]
    if missing:
        print(f"missing dump files in {args.dump_dir}: {', '.join(t + '.tsv' for t in missing)}", file=sys.stderr)
        return 2

    exp = {t: read_table(args.expected / f"{t}.tsv") for t in TABLES}
    act = {t: read_table(args.dump_dir / f"{t}.tsv") for t in TABLES}
    rep = Report()

    single_pass = args.profile == "single-pass"
    print(f"profile: {args.profile}" + ("  (account_src / account_curated / account_enriched NOT compared)"
                                        if single_pass else ""))
    compare_rejects(rep, exp["reject_log"], act["reject_log"], single_pass)
    compare_lineage(rep, exp["record_lineage"], act["record_lineage"])
    compare_keyed(rep, "account_target", exp["account_target"], act["account_target"], "account_id",
                  "each TARGET document as a JSON tree; run identity excluded")
    if not single_pass:
        for name, key in (("account_curated", "account_id"), ("account_enriched", "account_id"),
                          ("account_src", "acct_id")):
            compare_keyed(rep, name, exp[name], act[name], key, "every column except run/ingest metadata")
    compare_ledger(rep, act["run_ledger"], args.manifest)

    print()
    print(f"{'ALL CONFORMANCE CHECKS PASSED' if not rep.failures else str(len(rep.failures)) + ' FAILED'}"
          f"  ({rep.checks} checks, profile {args.profile})")
    return 1 if rep.failures else 0


if __name__ == "__main__":
    sys.exit(main())
