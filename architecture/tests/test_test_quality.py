"""[K05-4 K05-5] No empty tests: a test that asserts nothing is not a fitness function.

Python side: an AST walk — every `test_*` function needs an `assert` whose test is not a
bare constant and not a self-comparison (`assert x == x`); `with pytest.raises(...)`,
`pytest.fail(...)` and `assert_*` helpers count as assertions of their own kind.
JUnit side: every `@Test` / `@ParameterizedTest` / `@RepeatedTest` body must contain an
assert*, verify, fail( or check* call. The split is heuristic (annotation to annotation);
one deliberate delegated guard is named in JUNIT_ASSERT_DELEGATES with its reason.
"""
from __future__ import annotations

import ast
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SKIP = {"target", "work", "node_modules", "__pycache__", ".venv", ".git", "build", "dist", "out"}
JUNIT_ANNOTATION_RE = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest)\b")
JUNIT_NAME_RE = re.compile(r"\bvoid\s+(\w+)\s*\(")
JUNIT_ASSERT_RE = re.compile(r"\b(?:assert\w*|verify|fail|check\w*)\s*\(")

# A deliberate guard: its assertion throws inside the validator it drives, so the test
# cannot restate it locally without duplicating the validator. R2 migrates this into
# EXEMPTIONS.md when the register grows one.
JUNIT_ASSERT_DELEGATES = {
    ("TargetSchemaValidatorTest.java", "coversEveryConstraintDeclaredInTheSchema"),
}

EXCEPTION_ASSERTS = {"raises", "fail", "warning"}


def _call_name(node: ast.Call) -> str:
    f = node.func
    if isinstance(f, ast.Attribute):
        return f.attr
    if isinstance(f, ast.Name):
        return f.id
    return ""


def _has_exception_assertion(fn) -> bool:
    for n in ast.walk(fn):
        if isinstance(n, ast.Call):
            name = _call_name(n)
            if name in EXCEPTION_ASSERTS or name.startswith("assert_"):
                return True
    return False


def _is_constant_assert(node: ast.Assert) -> bool:
    test = node.test
    if isinstance(test, ast.Constant):
        return True
    if isinstance(test, ast.Compare) and len(test.comparators) == 1:
        if ast.dump(test.left) == ast.dump(test.comparators[0]):
            return True
    return False


def python_test_problems(path: Path) -> list[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    out = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) or not node.name.startswith("test"):
            continue
        asserts = [n for n in ast.walk(node) if isinstance(n, ast.Assert)]
        if not asserts:
            if not _has_exception_assertion(node):
                out.append(f"{path.name}:{node.name}: no assert at all")
        elif all(_is_constant_assert(a) for a in asserts):
            out.append(f"{path.name}:{node.name}: only constant assertions")
    return out


def java_test_problems(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    starts = [m.end() for m in JUNIT_ANNOTATION_RE.finditer(text)]
    out = []
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(text)
        segment = text[start:end]
        name_match = JUNIT_NAME_RE.search(segment)
        name = name_match.group(1) if name_match else "?"
        if (path.name, name) in JUNIT_ASSERT_DELEGATES:
            continue
        body = segment[name_match.end():] if name_match else segment
        if not JUNIT_ASSERT_RE.search(body):
            line = text.count("\n", 0, start) + 1
            out.append(f"{path.name}:{line}: {name}: no assert/verify/fail")
    return out


def _walk(root: Path, pattern: str):
    for p in root.rglob(pattern):
        if not any(part in SKIP for part in p.parts):
            yield p


def repo_test_problems(root: Path = REPO) -> list[str]:
    out: list[str] = []
    for area in ("spec", "architecture/tests", "implementations"):
        base = root / area
        if not base.exists():
            continue
        for p in _walk(base, "test_*.py"):
            out += python_test_problems(p)
    for p in _walk(root / "implementations", "*Test.java"):
        out += java_test_problems(p)
    return out


def test_no_empty_tests():
    problems = repo_test_problems()
    assert problems == [], "\n".join(problems)


def test_python_detector_fires(tmp_path):
    p = tmp_path / "test_x.py"
    p.write_text(
        "import pytest\n\n"
        "def test_empty():\n    pass\n\n"
        "def test_tautology():\n    x = 1\n    assert x == x\n\n"
        "def test_constant():\n    assert True\n\n"
        "def test_real():\n    assert 1 + 1 == 2\n\n"
        "def test_raises():\n    with pytest.raises(ValueError):\n        int('x')\n",
        encoding="utf-8",
    )
    assert python_test_problems(p) == [
        "test_x.py:test_empty: no assert at all",
        "test_x.py:test_tautology: only constant assertions",
        "test_x.py:test_constant: only constant assertions",
    ]


def test_junit_detector_fires(tmp_path):
    p = tmp_path / "EmptyTest.java"
    p.write_text(
        "class EmptyTest {\n"
        "  @Test\n"
        "  void assertsSomething() { assertThat(1).isEqualTo(1); }\n"
        "  @Test\n"
        "  void doesNothing() { runTheThing(); }\n"
        "}\n",
        encoding="utf-8",
    )
    problems = java_test_problems(p)
    assert len(problems) == 1, problems
    assert "doesNothing" in problems[0] and "no assert/verify/fail" in problems[0]
