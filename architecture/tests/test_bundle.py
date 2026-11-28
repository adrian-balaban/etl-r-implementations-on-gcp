"""[K06-4] The completeness flag lands outside the encrypted bundle.

The assertion: a consumer detects "the extract is complete" without holding a
decryption key. This harness builds a real bundle through spec/harness/bundle.py
and checks the layout; the negative case is bundle.py's own --no-flg switch.
"""
from __future__ import annotations

import importlib.util
import tarfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BUNDLE = REPO / "spec" / "harness" / "bundle.py"
SRC = REPO / "spec" / "fixtures" / "ACCOUNT.src"


def _bundle():
    spec = importlib.util.spec_from_file_location("spec_bundle", BUNDLE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def flag_problems(out_root: Path, run_id: str) -> list[str]:
    """Completeness must be readable without decrypting: the .FLG sits beside the
    archive, and when the archive is in the clear the flag must not be inside it."""
    incoming = out_root / run_id / "incoming"
    flg = incoming / "ACCOUNT.FLG"
    if not flg.exists():
        return ["ACCOUNT.FLG missing: completeness cannot be detected without a key"]
    problems, archives = [], sorted(incoming.glob("ACCOUNT.tar.gz*"))
    if not archives:
        problems.append("no bundle archive next to the flag")
    for archive in archives:
        if archive.suffix == ".pgp":
            continue  # encrypted: membership is the key-holder's business — the flag's point
        with tarfile.open(archive) as tar:
            names = tar.getnames()
        if "ACCOUNT.FLG" in names:
            problems.append(f"{archive.name}: the flag is INSIDE the bundle")
        if "ACCOUNT.CHS" not in names or "ACCOUNT.001.DAT" not in names:
            problems.append(f"{archive.name}: CHS/DAT members missing")
    return problems


def test_the_flag_lands_outside_the_bundle(tmp_path):
    _bundle().build(SRC, "run-check", tmp_path)
    assert flag_problems(tmp_path, "run-check") == []


def test_detector_fires_when_the_flag_is_withheld(tmp_path):
    _bundle().build(SRC, "run-check", tmp_path, no_flg=True)
    assert flag_problems(tmp_path, "run-check") == [
        "ACCOUNT.FLG missing: completeness cannot be detected without a key"
    ]
