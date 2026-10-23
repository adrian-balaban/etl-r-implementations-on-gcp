#!/usr/bin/env python3
"""Dump the seven pipeline tables for ONE run as TSV - the input of spec/conformance/compare.py.

    python local/scripts/dump_tables.py --run-id run-conf-20261017 --out work/conformance

One <table>.tsv per table, header row first, tab-delimited. Works against the emulator and
real BigQuery alike (it goes through pipelines.common.storage.BigQuery). The run-id column is
read from each table's schema (`_run_id` on account_src, `run_id` elsewhere) rather than assumed.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import decimal
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from pipelines.common.config import Config, require_identifier  # noqa: E402
from pipelines.common.storage import BigQuery  # noqa: E402


def cell(v: object) -> str:
    """One value as the oracle's TSV spells it: NULL is empty, JSON is JSON text."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (dict, list)):
        return json.dumps(v, separators=(",", ":"), sort_keys=True)
    if isinstance(v, decimal.Decimal):
        return format(v, "f")
    if isinstance(v, (dt.datetime, dt.date)):
        return v.isoformat()
    return str(v)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    run_id = require_identifier("--run-id", args.run_id)  # it is interpolated into SQL below
    cfg = Config.from_env()
    bq = BigQuery(cfg)
    tables = (
        ("account_src", cfg.ds_extraction),
        ("account_curated", cfg.ds_transformation),
        ("account_enriched", cfg.ds_transformation),
        ("account_target", cfg.ds_transformation),
        ("reject_log", cfg.ds_recon),
        ("record_lineage", cfg.ds_recon),
        ("run_ledger", cfg.ds_recon),
    )
    args.out.mkdir(parents=True, exist_ok=True)
    for table, dataset in tables:
        ref = f"{cfg.project}.{dataset}.{table}"
        columns = [f.name for f in bq.client.get_table(ref).schema]
        run_col = next((c for c in ("run_id", "_run_id") if c in columns), None)
        if run_col is None:
            raise SystemExit(f"{ref} has no run_id/_run_id column; columns are {columns}")
        rows = bq.query(f"SELECT * FROM `{ref}` WHERE {run_col} = '{run_id}'")
        with (args.out / f"{table}.tsv").open("w", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh, delimiter="\t", lineterminator="\n")
            w.writerow(columns)
            for r in rows:
                w.writerow([cell(r[c]) for c in columns])
        print(f"  {table:18s} {len(rows):4d} rows  ({ref})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
