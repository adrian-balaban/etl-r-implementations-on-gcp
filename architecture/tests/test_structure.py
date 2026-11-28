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
