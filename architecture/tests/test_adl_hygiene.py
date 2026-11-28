"""[C1] ADL hygiene — unique tags, resolvable bindings, one home per constant.

The register in GOVERNANCE.md assumes three invariants: a [Kxx-n] tag names one
assertion, an `AS <path>` binding points at a real file or directory, and a
DEFINE CONST name is defined once. This harness holds all three, and each check
has a failing case of its own (the course rule behind [K04-5]).
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ADL = REPO / "architecture" / "adl"

TAG_RE = re.compile(r"K\d{2}-\d+")
BINDING_RE = re.compile(r"^\s*DEFINE \S+(?: \S+)*? AS (\S*/\S*)\s*$", re.M)
CONST_RE = re.compile(r"^\s*DEFINE CONST (\w+)", re.M)


def adl_texts() -> dict[str, str]:
    return {p.name: p.read_text(encoding="utf-8") for p in sorted(ADL.glob("*.adl"))}


def _assertion_lines(text: str) -> str:
    """Tags live on assertion lines; a comment-only line may *reference* a tag without
    redefining it (the D1 and D7 fixes do exactly that), so comments are not counted."""
    return "\n".join(ln for ln in text.splitlines() if not ln.lstrip().startswith("#"))


def tag_homes(texts: dict[str, str]) -> dict[str, list[str]]:
    homes: dict[str, list[str]] = {}
    for name, text in texts.items():
        for tag in TAG_RE.findall(_assertion_lines(text)):
            homes.setdefault(tag, []).append(name)
    return homes


def duplicated_tags(texts: dict[str, str]) -> list[str]:
    return sorted(tag for tag, files in tag_homes(texts).items() if len(files) > 1)


def unresolvable_bindings(texts: dict[str, str], root: Path = REPO) -> list[str]:
    out = []
    for name, text in texts.items():
        for target in BINDING_RE.findall(text):
            target = target.rstrip("/")
            if not (root / target).exists():
                out.append(f"{name}: AS {target}")
    return out


def duplicated_constants(texts: dict[str, str]) -> list[str]:
    homes: dict[str, list[str]] = {}
    for name, text in texts.items():
        for const in CONST_RE.findall(text):
            homes.setdefault(const, []).append(name)
    return sorted(c for c, files in homes.items() if len(files) > 1)


def test_every_tag_names_one_assertion():
    assert duplicated_tags(adl_texts()) == [], f"tags reused: {duplicated_tags(adl_texts())}"


def test_every_binding_resolves():
    assert unresolvable_bindings(adl_texts()) == [], f"unresolved: {unresolvable_bindings(adl_texts())}"


def test_every_constant_is_defined_once():
    assert duplicated_constants(adl_texts()) == [], f"redefined: {duplicated_constants(adl_texts())}"


def test_duplicate_tag_detector_fires():
    texts = {"a.adl": "ASSERT(x) # [K03-1]", "b.adl": "ASSERT(y) # [K03-1]"}
    assert duplicated_tags(texts) == ["K03-1"]


def test_binding_detector_fires(tmp_path):
    (tmp_path / "implementations" / "ok").mkdir(parents=True)
    texts = {"a.adl": "DEFINE SERVICE s AS implementations/missing"}
    assert unresolvable_bindings(texts, tmp_path) == ["a.adl: AS implementations/missing"]


def test_constant_detector_fires():
    texts = {"a.adl": "DEFINE CONST MAX_CC AS 10", "b.adl": "DEFINE CONST MAX_CC AS 5"}
    assert duplicated_constants(texts) == ["MAX_CC"]
