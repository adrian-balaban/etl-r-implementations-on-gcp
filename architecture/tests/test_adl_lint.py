"""adl-lint — one harness for every K01–K18 family.

Every .adl file: a full header (DESCRIPTION, CATEGORY, NAMESPACE) and at least one
ASSERT; every [Kxx-n] tag traces to a kata folder that exists; the register
(GOVERNANCE.md) and the index (README.md) list everything. The negative checks
prove the lint can fail — the course rule: a fitness function ships with one
case that passes and one case that fails.
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ARCH = REPO / "architecture"
ADL = ARCH / "adl"
REQUIRED_HEADERS = ("DESCRIPTION", "CATEGORY", "NAMESPACE")
TAG_RE = re.compile(r"K\d{2}-\d+")


def kata_numbers() -> set[int]:
    return {int(p.name[1:3]) for p in ARCH.iterdir() if re.match(r"K\d{2}-", p.name)}


def adl_files():
    return sorted(ADL.glob("*.adl"))


def header_problems(text: str) -> list[str]:
    return [f"missing {h}" for h in REQUIRED_HEADERS if not re.search(rf"^{h} ", text, re.M)]


def tag_problems(text: str, known: set[int]) -> tuple[list[str], set[int]]:
    unknown, found = [], set()
    for m in TAG_RE.findall(text):
        n = int(m[1:3])
        if n not in known:
            unknown.append(m)
        found.add(n)
    return unknown, found


def test_every_adl_file_is_well_formed():
    for p in adl_files():
        text = p.read_text(encoding="utf-8")
        problems = header_problems(text)
        if "ASSERT(" not in text:
            problems.append("no ASSERT")
        assert problems == [], f"{p.name}: {problems}"


def test_every_tag_points_to_a_kata_folder():
    known = kata_numbers()
    for p in adl_files():
        unknown, _ = tag_problems(p.read_text(encoding="utf-8"), known)
        assert unknown == [], f"{p.name}: tags without a kata folder: {unknown}"


def test_every_kata_appears_in_the_register():
    known, tagged = kata_numbers(), set()
    for p in adl_files():
        _, found = tag_problems(p.read_text(encoding="utf-8"), known)
        tagged |= found
    missing = known - tagged
    assert not missing, f"katas without any tag in adl/: {sorted(missing)}"


def test_governance_and_index_list_everything():
    gov = (ARCH / "GOVERNANCE.md").read_text(encoding="utf-8")
    idx = (ARCH / "README.md").read_text(encoding="utf-8")
    for p in adl_files():
        assert p.name in gov, f"GOVERNANCE.md does not list {p.name}"
    for p in ARCH.iterdir():
        if re.match(r"K\d{2}-", p.name):
            assert p.name in idx, f"README.md does not link {p.name}"


def test_header_lint_fires_on_damage():
    assert header_problems("ASSERT(x)") == [f"missing {h}" for h in REQUIRED_HEADERS]


def test_tag_lint_fires_on_an_unknown_kata():
    unknown, _ = tag_problems("# [K13-1]", {1, 2})
    assert unknown == ["K13-1"]


def missing_register_rows(tags: set[str], register: str) -> list[str]:
    return sorted(t for t in tags if t not in register)


def test_every_tag_has_a_register_row():
    tags: set[str] = set()
    for p in adl_files():
        for line in p.read_text(encoding="utf-8").splitlines():
            if not line.lstrip().startswith("#"):
                tags.update(TAG_RE.findall(line))
    register = (ARCH / "GOVERNANCE.md").read_text(encoding="utf-8")
    missing = missing_register_rows(tags, register)
    assert missing == [], f"no register row: {missing}"


def test_register_lint_fires_on_a_missing_row():
    assert missing_register_rows({"K03-1", "K11-9"}, "| K03-1 |") == ["K11-9"]