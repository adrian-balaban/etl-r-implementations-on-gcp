"""[K07-1 K08-1] Every database access is declared — and no store is handed project-wide admin.

Rule 1: a JDBC URL in a non-test config or script must name a database its lane's quantum
declares (`DATABASE NAME` lines in static-coupling.adl).
Rule 2: a project-wide `roles/cloudsql.admin|editor` grant must be exempted in EXEMPTIONS.md
(owner, rationale, scope, expiry) — the TTL sweeper is the one deliberate case.
The scan is deliberately narrow: .sh/.yaml/.yml/.properties, test trees skipped.
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
QUANTUM_LANE = {
    "Flink Quantum": "flink",
    "FlinkJdbc Quantum": "flink-jdbc",
    "Beam Quantum": "beam-dataflow",
}
Q_RE = re.compile(r"^\s*DEFINE QUANTUM (.+?)\s*$")
DB_NAME_RE = re.compile(r"^\s*DATABASE NAME (\w+)\s*$")
URL_RE = re.compile(r"jdbc:postgresql://[^/\s\"'`]+/([A-Za-z0-9_]+)")
ADMIN_RE = re.compile(r'"roles/cloudsql\.(admin|editor)"')
EXEMPTION_ROW_RE = re.compile(r"^\|\s*([^|]+?)\s*\|[^|]*\|[^|]*\|\s*\d{4}-\d{2}-\d{2}\s*\|\s*$", re.M)
SCAN_EXTS = {".sh", ".yaml", ".yml", ".properties"}
SKIP = {"test", "target", "work", ".terraform", "node_modules", "__pycache__", ".git"}


def database_names(text: str) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        q = Q_RE.match(line)
        if q:
            current = q.group(1)
            out.setdefault(current, set())
            continue
        name = DB_NAME_RE.match(line)
        if current and name:
            out[current].add(name.group(1))
    return out


def _skipped(p: Path, root: Path) -> bool:
    """Match the repo-relative path: CI checks out under /home/runner/work/..."""
    try:
        parts = p.relative_to(root).parts
    except ValueError:
        parts = p.parts
    return any(part in SKIP for part in parts)


def jdbc_url_dbs(root: Path, lane: str) -> set[str]:
    base = root / "implementations" / lane
    found: set[str] = set()
    if not base.exists():
        return found
    for p in base.rglob("*"):
        if p.suffix not in SCAN_EXTS or _skipped(p, root):
            continue
        found.update(URL_RE.findall(p.read_text(encoding="utf-8", errors="replace")))
    return found


def admin_grants(root: Path) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    impls = root / "implementations"
    if not impls.exists():
        return out
    for p in impls.rglob("*.tf"):
        if _skipped(p, root):
            continue
        for role in sorted(set(ADMIN_RE.findall(p.read_text(encoding="utf-8", errors="replace")))):
            module = p.relative_to(impls).parent.as_posix()
            out.append((module, f"roles/cloudsql.{role}"))
    return out


def db_access_problems(root: Path = REPO, exemptions_text: str | None = None) -> list[str]:
    adl = root / "architecture" / "adl" / "static-coupling.adl"
    names = database_names(adl.read_text(encoding="utf-8")) if adl.exists() else {}
    exemptions = (
        exemptions_text
        if exemptions_text is not None
        else (root / "architecture" / "EXEMPTIONS.md").read_text(encoding="utf-8")
        if (root / "architecture" / "EXEMPTIONS.md").exists()
        else ""
    )
    scopes = [m.group(1) for m in EXEMPTION_ROW_RE.finditer(exemptions)]
    out = []
    for quantum, lane in QUANTUM_LANE.items():
        allowed = names.get(quantum, set())
        for db in sorted(jdbc_url_dbs(root, lane)):
            if db not in allowed:
                out.append(f"{lane}: JDBC URL database {db!r} is not declared for {quantum}")
    for module, role in admin_grants(root):
        if not any(module in scope for scope in scopes):
            out.append(f"{module}: project-wide {role} is not exempted in EXEMPTIONS.md")
    return out


def test_every_database_access_is_declared():
    problems = db_access_problems()
    assert problems == [], "\n".join(problems)


def test_url_rule_fires_on_an_undeclared_database(tmp_path):
    root = tmp_path / "r"
    (root / "architecture" / "adl").mkdir(parents=True)
    (root / "architecture" / "adl" / "static-coupling.adl").write_text(
        "DEFINE QUANTUM Flink Quantum\n     DEFINE DATABASE d AS mig-flink-pg\n          DATABASE NAME mig\n",
        encoding="utf-8",
    )
    harness = root / "implementations" / "flink" / "harness"
    harness.mkdir(parents=True)
    (harness / "run.sh").write_text('URL="jdbc:postgresql://localhost:5432/source"\n', encoding="utf-8")
    assert db_access_problems(root, exemptions_text="") == [
        "flink: JDBC URL database 'source' is not declared for Flink Quantum"
    ]


def test_admin_rule_fires_without_an_exemption(tmp_path):
    root = tmp_path / "r"
    module = root / "implementations" / "flink" / "terraform" / "modules" / "sweeper"
    module.mkdir(parents=True)
    (module / "main.tf").write_text('role = "roles/cloudsql.admin"\n', encoding="utf-8")
    assert db_access_problems(root, exemptions_text="") == [
        "flink/terraform/modules/sweeper: project-wide roles/cloudsql.admin is not exempted in EXEMPTIONS.md"
    ]
    exempted = "| C13 — `flink/terraform/modules/sweeper` | A | B | 2027-01-01 |\n"
    assert db_access_problems(root, exemptions_text=exempted) == []


def test_scanner_survives_a_workspace_path_containing_work(tmp_path):
    """CI checks out under /home/runner/work/...; the URL scan must still see the lane
    (before the fix it silently scanned nothing and the rule always passed)."""
    root = tmp_path / "work" / "etl-r"
    (root / "architecture" / "adl").mkdir(parents=True)
    (root / "architecture" / "adl" / "static-coupling.adl").write_text(
        "DEFINE QUANTUM Flink Quantum\n     DEFINE DATABASE d AS mig-flink-pg\n          DATABASE NAME mig\n",
        encoding="utf-8",
    )
    harness = root / "implementations" / "flink" / "harness"
    harness.mkdir(parents=True)
    (harness / "run.sh").write_text('URL="jdbc:postgresql://localhost:5432/undeclared"\n', encoding="utf-8")
    assert db_access_problems(root, exemptions_text="") == [
        "flink: JDBC URL database 'undeclared' is not declared for Flink Quantum"
    ]
