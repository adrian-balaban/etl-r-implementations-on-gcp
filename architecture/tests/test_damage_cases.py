"""[K04-5] Each comparator failure class keeps a damage case.

spec-test proves the comparator fails on damage; this check pins the class list, so a
failure class cannot quietly lose its damaged fixture. Each required test must also use
the `dump` fixture — a damage case that never drives the comparator proves nothing.
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
COMPARATOR_TESTS = REPO / "spec" / "conformance" / "test_compare.py"

SIG_RE = re.compile(r"^def (\w+)\(([^)]*)\)", re.M)

# failure class -> the test that damages the dump to prove the comparator catches it
CLASSES = {
    "test_a_changed_reject_detail_is_caught": "a changed reject detail",
    "test_a_changed_reject_reason_is_caught": "a changed reject reason",
    "test_wrong_stage_for_the_same_reason_is_caught": "a reason in the wrong stage",
    "test_a_missing_reject_row_is_caught": "a dropped reject row",
    "test_a_changed_target_document_is_caught": "a changed target document",
    "test_a_dropped_enriched_row_is_caught": "a dropped enriched row",
    "test_an_unbalanced_ledger_is_caught": "an unbalanced ledger",
    "test_a_ledger_tally_outside_the_manifest_is_caught": "a tally outside the manifest",
    "test_a_missing_table_is_a_usage_error_not_a_pass": "a missing table (usage error)",
}


def damage_problems(text: str) -> list[str]:
    signatures = {m.group(1): m.group(2) for m in SIG_RE.finditer(text)}
    out = []
    for name, failure_class in CLASSES.items():
        if name not in signatures:
            out.append(f"missing damage case: {failure_class} ({name})")
        elif "dump" not in signatures[name]:
            out.append(f"{name}: does not use the dump fixture, so it damages nothing")
    return out


def test_every_failure_class_has_a_damage_case():
    problems = damage_problems(COMPARATOR_TESTS.read_text(encoding="utf-8"))
    assert problems == [], "\n".join(problems)


def test_detector_fires_on_a_trimmed_suite():
    problems = damage_problems("def test_something(dump):\n    pass\n")
    assert len(problems) == len(CLASSES)
    assert any("a changed target document" in p for p in problems)
