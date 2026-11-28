"""[C2] The lane graph is acyclic — the paper rule [K03-4] promoted to a check.

Reads what the lanes actually reference (path tokens in code and config, the
reactor and Terraform paths), builds the directed graph, and fails on a cycle.
D11 removed the last flink -> flink-jdbc reference, so today the only edges run
jdbc -> flink (the deliberate sharing pinned in test_boundaries.SHARED_WITH).

The scanner helpers mirror test_boundaries.py on purpose: the structure checks
stay independent of each other, so a broken boundary rule cannot silence them.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SKIP_DIRS = {".git", ".venv", ".terraform", "__pycache__", ".pytest_cache",
             "node_modules", "target", "work", "dist", "out", "build", ".idea", ".vscode"}
SCANNED_EXTS = {".java", ".py", ".sh", ".tf", ".tfvars", ".yaml", ".yml", ".json",
                ".xml", ".properties", ".sql", ".sqlx", ".cfg", ".toml", ".template"}

REF_RE = ("implementations/{sib}(?![\\w-])", "(?:\\.\\./)+{sib}(?![\\w-])")


def impl_names(root: Path) -> list[str]:
    d = root / "implementations"
    return sorted(p.name for p in d.iterdir() if p.is_dir() and not p.name.startswith("."))


def iter_source_files(root: Path, impl: str):
    base = root / "implementations" / impl
    for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            p = Path(dirpath) / name
            if p.is_symlink():
                continue
            if p.suffix in SCANNED_EXTS or name.startswith(("Makefile", "Dockerfile")):
                yield p


def _drop_comment_lines(text: str, markers: tuple[str, ...]) -> str:
    return "\n".join("" if ln.lstrip().startswith(markers) else ln for ln in text.splitlines())


def code_text(p: Path) -> str:
    text = p.read_text(encoding="utf-8", errors="replace")
    if p.suffix == ".java":
        return _drop_comment_lines(text, ("//",))
    if p.suffix in {".sql", ".sqlx"}:
        return _drop_comment_lines(text, ("--", "#"))
    return _drop_comment_lines(text, ("#",))


def lane_graph(root: Path = REPO) -> dict[str, set[str]]:
    names = impl_names(root)
    edges = {n: set() for n in names}
    for impl in names:
        for p in iter_source_files(root, impl):
            text = code_text(p)
            for sib in names:
                if sib == impl:
                    continue
                for rx in REF_RE:
                    if re.search(rx.format(sib=re.escape(sib)), text):
                        edges[impl].add(sib)
                        break
    return edges


def find_cycle(graph: dict[str, set[str]]) -> list[str] | None:
    color = {n: 0 for n in graph}
    path: list[str] = []

    def dfs(n: str) -> list[str] | None:
        color[n] = 1
        path.append(n)
        for m in graph.get(n, ()):
            if color.get(m, 0) == 1:
                return path[path.index(m):] + [m]
            if color.get(m, 0) == 0:
                found = dfs(m)
                if found:
                    return found
        color[n] = 2
        path.pop()
        return None

    for n in graph:
        if color[n] == 0:
            found = dfs(n)
            if found:
                return found
    return None


def test_the_lane_graph_is_acyclic():
    cycle = find_cycle(lane_graph())
    assert cycle is None, f"cycle: {' -> '.join(cycle or [])}"


def test_cycle_detector_fires_on_a_plain_graph():
    assert find_cycle({"a": {"b"}, "b": {"a"}}) == ["a", "b", "a"]


def test_cycle_detector_fires_on_a_lane_reference(tmp_path):
    root = tmp_path / "r"
    aa, bb = root / "implementations" / "aa", root / "implementations" / "bb"
    aa.mkdir(parents=True)
    bb.mkdir(parents=True)
    (aa / "x.py").write_text("open('../../bb/x')\n", encoding="utf-8")
    (bb / "y.py").write_text("open('../../aa/y')\n", encoding="utf-8")
    graph = lane_graph(root)
    assert graph == {"aa": {"bb"}, "bb": {"aa"}}
    assert find_cycle(graph) == ["aa", "bb", "aa"]


# --- C3: every lane-root entry has an architectural home [K01-1] --------------------

SERVICE_RE = re.compile(r"^\s*DEFINE SERVICE \S+ AS implementations/(\S+)\s*$")
COMPONENT_RE = re.compile(r"^\s*DEFINE COMPONENT \S+ AS (\S+)\s*$")

SHARED_ALLOW = {
    "Makefile", "pom.xml", "CLAUDE.md", "work", "contracts", "fixtures", "harness",
    "terraform", "gcp", "sql", "local", "local-development-podman", "requirements.txt",
}
# Entries that are the lane's own, beyond its declared components; each needs a reason.
LANE_ALLOW = {
    # the Target System mock app — governance.adl assigns it its own team
    "beam-dataflow": {"apps"},
    # alternative job variants of the one service (docs/diagrams/08-stage-mapping.svg)
    "flink": {"variant-flink-datastream-batch-job", "variant-flink-sql-batch-job"},
    "flink-jdbc": set(),
}


def declared_components(text: str) -> dict[str, set[str]]:
    out: dict[str, set[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        m = SERVICE_RE.match(line)
        if m:
            current = m.group(1)
            out.setdefault(current, set())
            continue
        if re.match(r"^\s*DEFINE (DOMAIN|SYSTEM|LIBRARY)\b", line):
            current = None
            continue
        c = COMPONENT_RE.match(line) if current else None
        if c:
            out[current].add(c.group(1).strip().rstrip("/"))
    return out


def stray_root_entries(root: Path = REPO) -> list[str]:
    adl = root / "architecture" / "adl" / "structure.adl"
    declared = declared_components(adl.read_text(encoding="utf-8")) if adl.exists() else {}
    out = []
    for impl in impl_names(root):
        comps, allow = declared.get(impl, set()), SHARED_ALLOW | LANE_ALLOW.get(impl, set())
        for entry in sorted((root / "implementations" / impl).iterdir()):
            name = entry.name
            if name.startswith("."):
                continue
            if (name in comps or name in allow or name.startswith("Dockerfile")
                    or name.endswith(".dockerignore")):
                continue
            out.append(f"{impl}/{name}")
    return out


def test_every_lane_root_entry_has_a_home():
    assert stray_root_entries() == [], f"no architectural home: {stray_root_entries()}"


def test_stray_entry_detector_fires(tmp_path):
    root = tmp_path / "r"
    (root / "architecture" / "adl").mkdir(parents=True)
    (root / "architecture" / "adl" / "structure.adl").write_text(
        "DEFINE SYSTEM X AS x\n"
        "     DEFINE SERVICE aa AS implementations/aa\n"
        "          DEFINE COMPONENT Gate AS gate\n",
        encoding="utf-8",
    )
    lane = root / "implementations" / "aa"
    (lane / "gate").mkdir(parents=True)
    (lane / "Makefile").write_text("", encoding="utf-8")
    (lane / "stray").mkdir()
    assert stray_root_entries(root) == ["aa/stray"]


# --- C4: one contract copy — lane contracts/fixtures are symlinks into spec/ [K02-2] --

def copied_contracts(root: Path = REPO) -> list[str]:
    out = []
    spec = root / "spec"
    for impl in impl_names(root):
        base = root / "implementations" / impl
        for name in ("contracts", "fixtures"):
            p = base / name
            if not (p.is_symlink() or p.exists()):
                continue
            if not p.is_symlink():
                out.append(f"{impl}/{name}: a real directory, not a symlink into spec/")
                continue
            if not p.resolve().is_relative_to(spec):
                out.append(f"{impl}/{name} -> {p.resolve()} (outside spec/)")
    return out


def test_lane_contracts_are_symlinks_into_spec():
    assert copied_contracts() == [], f"contract copies: {copied_contracts()}"


def test_copy_detector_fires(tmp_path):
    root = tmp_path / "r"
    (root / "spec" / "fixtures").mkdir(parents=True)
    (root / "spec" / "contracts").mkdir()
    (root / "implementations" / "aa" / "contracts").mkdir(parents=True)
    bb = root / "implementations" / "bb"
    bb.mkdir(parents=True)
    (bb / "fixtures").symlink_to(root / "spec" / "fixtures")
    assert copied_contracts(root) == ["aa/contracts: a real directory, not a symlink into spec/"]


