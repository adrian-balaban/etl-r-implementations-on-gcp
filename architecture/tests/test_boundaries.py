"""[K03-1..3] The boundaries between implementations — a runnable fitness function.

The repo's reality (GOVERNANCE.md §§3, 7): beam-dataflow is strictly independent
of both Flink lanes, and flink references no sibling. flink-jdbc deliberately
extends the flink family — Maven modules taken into its reactor, the mig.common
classes, Terraform modules, test fixtures — and that sharing is PINNED below:
the listed files may reference only the sibling in their value, nothing else.
Any cross-lane reference outside the list fails the suite; the list only ever
shrinks. Sharing with spec/ goes through symlinks only (CLAUDE.md).

Limits: Java import lines + path tokens in code and config, comments stripped;
Markdown not scanned; Maven GAV coordinates not matched (the reactor <module>
paths are).
"""
import os
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SKIP_DIRS = {".git", ".venv", ".terraform", "__pycache__", ".pytest_cache",
             "node_modules", "target", "work", "dist", "out", "build", ".idea", ".vscode"}
SCANNED_EXTS = {".java", ".py", ".sh", ".tf", ".tfvars", ".yaml", ".yml", ".json",
                ".xml", ".properties", ".sql", ".sqlx", ".cfg", ".toml", ".template"}

# The deliberate sharing inside the flink family, pinned: file (relative to implementations/) -> {allowed siblings}
SHARED_WITH = {
    "flink-jdbc/pom.xml": {"flink"},
    "flink-jdbc/Makefile": {"flink"},
    "flink-jdbc/gcp/orchestrator/run.sh": {"flink"},
    "flink-jdbc/local-development-podman/podman-compose.yml": {"flink"},
    "flink-jdbc/sql/postgres-init-jdbc.sql": {"flink"},
    "flink-jdbc/terraform/envs/dev/main.tf": {"flink"},
    "flink-jdbc/gate-jdbc/src/test/java/mig/gatejdbc/JdbcTerraformContractTest.java": {"flink"},
    "flink-jdbc/gate-jdbc/src/test/java/mig/gatejdbc/JdbcOrchestratorBehaviourTest.java": {"flink"},
    "flink-jdbc/gate-jdbc/src/test/java/mig/gatejdbc/SeederBehaviourTest.java": {"flink"},
    "flink-jdbc/component-tests-jdbc/src/test/java/mig/jdbclane/Podman.java": {"flink"},
    "flink-jdbc/component-tests-jdbc/src/test/java/mig/sql/JdbcIntakeJobIT.java": {"flink"},
}
# The sibling-library import (K03's "share" verdict): (importer, sibling, package)
ALLOWED_PACKAGE_IMPORTS = {("flink-jdbc", "flink", "mig.common")}

PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", re.M)


def impl_names(root: Path) -> list[str]:
    d = root / "implementations"
    return sorted(p.name for p in d.iterdir() if p.is_dir() and not p.name.startswith("."))


def rel(p: Path, root: Path) -> str:
    return p.relative_to(root / "implementations").as_posix()


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


def _strip_blocks(text: str, opener: str, closer: str) -> str:
    out, i, n, in_block = [], 0, len(text), False
    while i < n:
        if not in_block and text.startswith(opener, i):
            in_block = True
            out.append(" ")
            i += len(opener)
        elif in_block and text.startswith(closer, i):
            in_block = False
            i += len(closer)
        else:
            if not in_block:
                out.append(text[i])
            i += 1
    return "".join(out)


def _drop_marker_lines(text: str, marker: str) -> str:
    return "\n".join("" if ln.lstrip().startswith(marker) else ln for ln in text.splitlines())


def strip_comments(text: str, p: Path) -> str:
    if p.suffix == ".java":
        return _drop_marker_lines(_strip_blocks(text, "/*", "*/"), "//")
    if p.suffix == ".xml":
        return _strip_blocks(text, "<!--", "-->")
    if p.suffix in {".sql", ".sqlx"}:
        return _drop_marker_lines(_drop_marker_lines(text, "--"), "#")
    return _drop_marker_lines(text, "#")


def _code_text(p: Path) -> str:
    return strip_comments(p.read_text(encoding="utf-8", errors="replace"), p)


def path_violations(root: Path = REPO) -> list[str]:
    out, names = [], impl_names(root)
    for impl in names:
        for p in iter_source_files(root, impl):
            key = rel(p, root)
            allowed = SHARED_WITH.get(key, set())
            text = _code_text(p)
            for sib in [s for s in names if s != impl and s not in allowed]:
                for rx in (rf"implementations/{sib}(?![\w-])", rf"(?:\.\./)+{sib}(?![\w-])"):
                    m = re.search(rx, text)
                    if m:
                        line = text.count("\n", 0, m.start()) + 1
                        out.append(f"{key}:{line}: sibling {sib!r} ({m.group(0)!r})")
    return out


def package_map(root: Path = REPO) -> dict[str, set[str]]:
    out = {}
    for impl in impl_names(root):
        pkgs = set()
        for p in iter_source_files(root, impl):
            if p.suffix == ".java":
                pkgs.update(PACKAGE_RE.findall(_code_text(p)))
        out[impl] = pkgs
    return out


def import_violations(root: Path = REPO) -> list[str]:
    out, names, pkgs = [], impl_names(root), package_map(root)
    for impl in names:
        foreign: dict[str, str] = {}
        for sib in names:
            if sib != impl:
                for pg in pkgs[sib]:
                    foreign.setdefault(pg, sib)
        own = pkgs[impl]
        for p in iter_source_files(root, impl):
            if p.suffix != ".java":
                continue
            for m in IMPORT_RE.finditer(_code_text(p)):
                for pg, provider in foreign.items():
                    if pg in own or (impl, provider, pg) in ALLOWED_PACKAGE_IMPORTS:
                        continue
                    if m.group(1) == pg or m.group(1).startswith(pg + "."):
                        out.append(f"{rel(p, root)}: import {pg}.* (from {provider})")
    return out


def symlink_violations(root: Path = REPO) -> list[str]:
    out = []
    for impl in impl_names(root):
        base = (root / "implementations" / impl).resolve()
        for dirpath, dirnames, filenames in os.walk(base, followlinks=False):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in dirnames + filenames:
                p = Path(dirpath) / name
                if not p.is_symlink():
                    continue
                target = p.resolve()
                if not (target.is_relative_to(root / "spec") or target.is_relative_to(base)):
                    out.append(f"{rel(p, root)} -> {target}")
    return out


def test_symlinks_point_into_spec_or_own_lane():
    assert symlink_violations() == [], "\n".join(symlink_violations())


def test_no_new_cross_lane_paths():
    assert path_violations() == [], "\n".join(path_violations())


def test_no_new_cross_lane_imports():
    assert import_violations() == [], "\n".join(import_violations())


def _mini_repo(tmp_path: Path) -> Path:
    root = tmp_path / "r"
    aa = root / "implementations" / "aa"
    bb = root / "implementations" / "bb"
    (bb / "src").mkdir(parents=True)
    aa.mkdir(parents=True)
    (bb / "src" / "B.java").write_text("package bb.only;\nclass B {}\n", encoding="utf-8")
    (aa / "T.java").write_text("import bb.only.B;\npublic class T {}\n", encoding="utf-8")
    (aa / "s.py").write_text("# a comment: implementations/bb\nopen('../../bb/x')\n", encoding="utf-8")
    (aa / "clean.sh").write_text("# just a comment: implementations/bb\n", encoding="utf-8")
    (aa / "link").symlink_to("../bb")
    return root


def test_path_detector_fires_and_ignores_comments(tmp_path):
    v = path_violations(_mini_repo(tmp_path))
    assert any("s.py" in x for x in v), "the in-code reference must be caught"
    assert not any("clean.sh" in x for x in v), "the comment must not be flagged"


def test_import_detector_fires(tmp_path):
    v = import_violations(_mini_repo(tmp_path))
    assert any(x.startswith("aa/T.java") and "bb.only" in x for x in v)


def test_symlink_detector_fires(tmp_path):
    v = symlink_violations(_mini_repo(tmp_path))
    assert any(x.startswith("aa/link") for x in v), v