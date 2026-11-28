"""[K04-4 K14-5] Distance from the main sequence, per Maven module — a ratchet.

A = abstractness (interfaces + abstract classes over all type declarations),
I = instability (Ce / (Ca + Ce) over module-to-module imports), D = |A + I - 1|.
Threshold MAX_DISTANCE = 0.3. Measured 2026-11-28, three modules sit past it and the rest sit
on the sequence: the numbers are the repo's reality, so the gate pins them and fails on
  * a NEW module with D >= MAX_DISTANCE (record its baseline deliberately), and
  * a pinned module whose D GROWS.
The full table prints on every run.
"""
from __future__ import annotations

import pathlib
import re

REPO = pathlib.Path(__file__).resolve().parents[2]
MAX_DISTANCE = 0.3
TOLERANCE = 0.02
SKIP = {".venv", "target", "work", ".terraform", "node_modules", "__pycache__", ".git", "build", "dist"}

# module path (relative to the repo) -> pinned D, measured 2026-11-28
PINNED_D: dict[str, float] = {
    "implementations/beam-dataflow/apps/common": 0.86,
    "implementations/beam-dataflow/apps/extract": 0.00,
    "implementations/beam-dataflow/apps/load": 0.08,
    "implementations/beam-dataflow/apps/recon": 0.00,
    "implementations/beam-dataflow/apps/target-system-mock": 0.00,
    "implementations/flink-jdbc/gate-jdbc": 0.00,
    "implementations/flink-jdbc/intake-jdbc": 1.00,
    "implementations/flink/common": 1.00,
    "implementations/flink/gate": 1.00,
    "implementations/flink/recon": 0.00,
    "implementations/flink/target-system-mock": 0.00,
    "implementations/flink/variant-flink-datastream-batch-job": 0.00,
    "implementations/flink/variant-flink-sql-batch-job": 0.00,
}

PACKAGE_RE = re.compile(r"^package\s+([\w.]+)", re.M)
IMPORT_RE = re.compile(r"^import\s+(?:static\s+)?([\w.]+);", re.M)
TYPE_RE = re.compile(r"\b(?:class|interface|enum|record)\s+\w+")
ABSTRACT_RE = re.compile(r"\binterface\s+\w+|\babstract\s+class\s+\w+")


def java_modules(root: pathlib.Path = REPO) -> dict[str, dict]:
    modules: dict[str, dict] = {}
    for d in root.glob("implementations/**/src/main/java"):
        module = _rel(d.parent.parent.parent, root)
        packages, imports, types, abstracts = set(), set(), 0, 0
        for j in d.rglob("*.java"):
            text = j.read_text(encoding="utf-8", errors="replace")
            m = PACKAGE_RE.search(text)
            if m:
                packages.add(m.group(1))
            imports.update(IMPORT_RE.findall(text))
            types += len(TYPE_RE.findall(text))
            abstracts += len(ABSTRACT_RE.findall(text))
        modules[module] = {"packages": packages, "imports": imports, "types": types, "abstracts": abstracts}
    return modules


def _rel(p: pathlib.Path, root: pathlib.Path) -> str:
    return p.relative_to(root).as_posix()


def main_sequence(root: pathlib.Path = REPO) -> dict[str, tuple[float, float, float]]:
    modules = java_modules(root)
    deps: dict[str, set[str]] = {m: set() for m in modules}
    for module, info in modules.items():
        for other, oinfo in modules.items():
            if other == module:
                continue
            if any(i == pg or i.startswith(pg + ".") for i in info["imports"] for pg in oinfo["packages"]):
                deps[module].add(other)
    out: dict[str, tuple[float, float, float]] = {}
    for module, info in modules.items():
        ce = len(deps[module])
        ca = sum(1 for other in modules if module in deps[other] and other != module)
        instability = ce / (ca + ce) if ca + ce else 0.0
        abstractness = info["abstracts"] / info["types"] if info["types"] else 0.0
        out[module] = (abstractness, instability, abs(abstractness + instability - 1.0))
    return out


def sequence_problems(measured: dict[str, tuple[float, float, float]],
                      pinned: dict[str, float]) -> list[str]:
    out = []
    for module, (_, _, distance) in sorted(measured.items()):
        if module not in pinned:
            if distance >= MAX_DISTANCE:
                out.append(f"{module}: NEW module at D={distance:.2f} (budget {MAX_DISTANCE}) — record a baseline deliberately")
        elif distance > pinned[module] + TOLERANCE:
            out.append(f"{module}: D grew {pinned[module]:.2f} -> {distance:.2f}")
    return out


def test_no_new_distance_from_the_main_sequence():
    problems = sequence_problems(main_sequence(), PINNED_D)
    assert problems == [], "\n".join(problems)


def test_ratchet_fires_on_a_new_off_sequence_module():
    problems = sequence_problems({"m/new": (0.0, 0.0, 1.0)}, {})
    assert problems == ["m/new: NEW module at D=1.00 (budget 0.3) — record a baseline deliberately"]


def test_ratchet_fires_when_distance_grows():
    problems = sequence_problems({"m/a": (0.0, 0.0, 0.5)}, {"m/a": 0.2})
    assert problems == ["m/a: D grew 0.20 -> 0.50"]
