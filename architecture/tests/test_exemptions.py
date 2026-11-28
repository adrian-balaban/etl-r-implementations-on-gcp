"""[R3] An expired exemption fails the harness.

EXEMPTIONS.md is the one place a failed assertion may stay failed (checklist §10.9): owner,
rationale, scope, expiry. A row past its date is a Fail at the next pass — this check is
that pass.
"""
from __future__ import annotations

import datetime
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EXEMPTIONS = REPO / "architecture" / "EXEMPTIONS.md"

ROW_RE = re.compile(r"^\|\s*([^|]+?)\s*\|[^|]*\|[^|]*\|\s*(\d{4}-\d{2}-\d{2})\s*\|\s*$", re.M)


def expired_scopes(text: str, today: datetime.date) -> list[str]:
    out = []
    for m in ROW_RE.finditer(text):
        if datetime.date.fromisoformat(m.group(2)) < today:
            out.append(m.group(1).strip())
    return out


def test_no_exemption_is_expired():
    expired = expired_scopes(EXEMPTIONS.read_text(encoding="utf-8"), datetime.date.today())
    assert expired == [], f"expired exemptions: {expired}"


def test_detector_fires_on_an_expired_row():
    text = "| `X` — a scope | Adrian Balaban | because | 2020-01-01 |\n"
    assert expired_scopes(text, datetime.date(2026, 11, 28)) == ["`X` — a scope"]
