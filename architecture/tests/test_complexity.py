"""[K04-1 K04-2 K04-3] Complexity is a ratchet, not a wall.

MAX_CC is 10. The repo has 30 honest hotspots above it today (the mapping engine, the record
parsers) — essential domain complexity, not accidental. So the gate works like the boundary
pins: the current set is recorded, and
  * a NEW function above MAX_CC fails (write it under 10, or pin it with a reason),
  * a pinned function whose complexity GROWS fails (the ratchet only turns one way), and
  * a pin whose function disappeared fails (keep the baseline honest).
The report prints on every run, so the numbers are visible, not just the verdict.
Tools: `radon` (Python), `lizard` (Java) — pinned in the root Makefile.
"""
from __future__ import annotations

import pathlib

import lizard
import radon.complexity as radon_cc

REPO = pathlib.Path(__file__).resolve().parents[2]
MAX_CC = 10
SKIP = {".venv", "target", "work", ".terraform", "node_modules", "__pycache__", ".git", "build", "dist"}

# The baseline, measured 2026-11-28: (relative path, function/method name) -> cyclomatic complexity
PINNED: dict[tuple[str, str], int] = {
    ("implementations/beam-dataflow/pipelines/intake/pipeline.py", "process"): 19,
    ("spec/fixtures/db/derive.py", "derive"): 19,
    ("implementations/beam-dataflow/pipelines/common/tds.py", "_parse_dat"): 16,
    ("implementations/beam-dataflow/pipelines/common/tds.py", "_coerce"): 16,
    ("implementations/beam-dataflow/pipelines/common/mapping.py", "load_mapping"): 15,
    ("implementations/beam-dataflow/pipelines/produce/pipeline.py", "run_pipeline"): 14,
    ("implementations/beam-dataflow/pipelines/common/tds.py", "parse_tds"): 13,
    ("spec/conformance/compare.py", "main"): 12,
    ("implementations/beam-dataflow/pipelines/common/tds.py", "_require_homogeneous"): 12,
    ("implementations/beam-dataflow/pipelines/common/engine.py", "run_batches"): 11,
    ("implementations/flink/common/src/main/java/mig/common/Mapping.java", "Mapping::Engine::applyTransform"): 21,
    ("implementations/beam-dataflow/apps/load/src/main/java/ro/mig/loader/LoaderApp.java", "LoaderApp::Args::parse"): 19,
    ("implementations/flink/common/src/main/java/mig/common/TargetSchemaValidator.java", "TargetSchemaValidator::checkValue"): 17,
    ("implementations/flink/common/src/main/java/mig/common/Tds.java", "Tds::parse"): 16,
    ("implementations/flink/common/src/main/java/mig/common/Tds.java", "Tds::coerce"): 16,
    ("implementations/flink/recon/src/main/java/mig/recon/ReconService.java", "ReconService::main"): 16,
    ("implementations/flink/common/src/main/java/mig/common/Mapping.java", "Mapping::load"): 16,
    ("implementations/beam-dataflow/apps/recon/src/main/java/ro/mig/recon/ReconService.java", "ReconService::main"): 15,
    ("implementations/beam-dataflow/apps/recon/src/main/java/ro/mig/recon/ReconService.java", "ReconService::Args::parse"): 15,
    ("implementations/flink/gate/src/main/java/mig/gate/Gate.java", "Gate::run"): 15,
    ("implementations/beam-dataflow/apps/target-system-mock/src/main/java/ro/mig/vault/TargetSystemMock.java", "TargetSystemMock::handleAccounts"): 14,
    ("implementations/flink/gate/src/main/java/mig/gate/Pgp.java", "Pgp::decrypt"): 14,
    ("implementations/beam-dataflow/apps/common/src/main/java/ro/mig/common/BigQueryRest.java", "BigQueryRest::query"): 14,
    ("implementations/flink/common/src/main/java/mig/common/Tds.java", "Tds::RecordParser::parseDat"): 13,
    ("implementations/beam-dataflow/apps/load/src/main/java/ro/mig/loader/ReturnStream.java", "ReturnStream::settle"): 13,
    ("implementations/flink/common/src/main/java/mig/common/Tds.java", "Tds::requireHomogeneous"): 12,
    ("implementations/beam-dataflow/apps/recon/src/main/java/ro/mig/recon/Html.java", "Html::render"): 12,
    ("implementations/beam-dataflow/apps/load/src/main/java/ro/mig/loader/LoaderApp.java", "LoaderApp::main"): 11,
    ("implementations/beam-dataflow/apps/load/src/main/java/ro/mig/loader/LoaderApp.java", "LoaderApp::HttpSink::send"): 11,
    ("implementations/beam-dataflow/apps/extract/src/main/java/ro/mig/extractor/ExtractorApp.java", "ExtractorApp::Args::parse"): 11,
}


def _rel(p: pathlib.Path, root: pathlib.Path) -> str:
    return p.relative_to(root).as_posix()


def _skipped(p: pathlib.Path, root: pathlib.Path) -> bool:
    """Match on the repo-relative path: CI checks out under /home/runner/work/..., and a
    parts-based match on the absolute path skips everything there."""
    try:
        parts = p.relative_to(root).parts
    except ValueError:
        parts = p.parts
    return any(part in SKIP for part in parts)


def python_hotspots(root: pathlib.Path = REPO) -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = {}
    sources = list(root.glob("spec/**/*.py")) + list(root.glob("implementations/**/*.py"))
    for p in sources:
        if _skipped(p, root) or p.name.startswith("test_") or "tests" in p.parts:
            continue
        try:
            blocks = radon_cc.cc_visit(p.read_text(encoding="utf-8", errors="replace"))
        except SyntaxError:
            continue
        for block in blocks:
            if block.complexity > MAX_CC:
                out[(_rel(p, root), block.name)] = block.complexity
    return out


def java_hotspots(root: pathlib.Path = REPO) -> dict[tuple[str, str], int]:
    out: dict[tuple[str, str], int] = {}
    for p in root.glob("implementations/**/src/main/java/**/*.java"):
        if _skipped(p, root):
            continue
        try:
            analysis = lizard.analyze_file(str(p))
        except Exception:
            continue
        for fn in analysis.function_list:
            if fn.cyclomatic_complexity > MAX_CC:
                out[(_rel(p, root), fn.name)] = fn.cyclomatic_complexity
    return out


def complexity_report(root: pathlib.Path = REPO) -> dict[tuple[str, str], int]:
    return {**python_hotspots(root), **java_hotspots(root)}


def ratchet_problems(measured: dict[tuple[str, str], int],
                     pinned: dict[tuple[str, str], int]) -> list[str]:
    out = []
    for key, cc in sorted(measured.items()):
        if key not in pinned:
            out.append(f"{key[0]}: {key[1]} is a NEW hotspot at {cc} (budget {MAX_CC}) — refactor or pin with a reason")
        elif cc > pinned[key]:
            out.append(f"{key[0]}: {key[1]} grew {pinned[key]} -> {cc}")
    for key, cc in sorted(pinned.items()):
        if key not in measured:
            out.append(f"{key[0]}: pinned {key[1]} ({cc}) is gone — update the baseline")
    return out


def test_no_new_complexity_hotspots():
    measured = complexity_report()
    print(f"complexity report: {len(measured)} hotspots above {MAX_CC}, worst {max(measured.values(), default=0)}")
    problems = ratchet_problems(measured, PINNED)
    assert problems == [], "\n".join(problems)


def test_ratchet_fires_on_a_new_hotspot():
    problems = ratchet_problems({("x.py", "f"): 12}, {})
    assert problems == ["x.py: f is a NEW hotspot at 12 (budget 10) — refactor or pin with a reason"]


def test_ratchet_fires_when_a_pinned_function_grows():
    assert ratchet_problems({("x.py", "f"): 13}, {("x.py", "f"): 12}) == ["x.py: f grew 12 -> 13"]


def test_ratchet_fires_when_a_pin_disappears():
    assert ratchet_problems({}, {("x.py", "f"): 12}) == ["x.py: pinned f (12) is gone — update the baseline"]


def test_scanner_survives_a_workspace_path_containing_work(tmp_path):
    """The GitHub runner checks out under /home/runner/work/... — a parts-based skip on the
    absolute path once skipped the whole repo and turned every pin into a false 'gone'."""
    root = tmp_path / "work" / "etl-r"
    lane = root / "implementations" / "x"
    lane.mkdir(parents=True)
    body = "".join(f"    if a == {i}:\n        return {i}\n" for i in range(12))
    (lane / "y.py").write_text(f"def wide(a):\n{body}    return -1\n", encoding="utf-8")
    assert ("implementations/x/y.py", "wide") in python_hotspots(root)

