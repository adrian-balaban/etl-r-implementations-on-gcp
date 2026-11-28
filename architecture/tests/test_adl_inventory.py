"""[K16-2] No technology inventory in the ADL.

The stacks are harvested from build files and the SBOM (K16's verdict); an ADL that
starts naming versions is an inventory that rots. This check forbids technology+version
pairs and any bare `USING` — a deliberate `USING` gets added here with its reason, never
smuggled in.
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ADL = REPO / "architecture" / "adl"

TECH_VERSION_RE = re.compile(
    r"\b(?:Flink|Beam|Kafka|Kubernetes|Postgres(?:QL)?|Java|Python|Maven|Gradle|Airflow|"
    r"Composer|Dataflow|Dataform|BigQuery|CycloneDX|Spark|Hadoop)\s+v?\d+(?:\.\d+)*\b",
    re.IGNORECASE,
)
USING_RE = re.compile(r"\bUSING\b")
# Deliberate USING clauses, each with the reason it is allowed. Empty on purpose.
ALLOWED_USING: dict[str, str] = {}


def adl_texts() -> dict[str, str]:
    return {p.name: p.read_text(encoding="utf-8") for p in sorted(ADL.glob("*.adl"))}


def inventory_problems(texts: dict[str, str] | None = None) -> list[str]:
    texts = adl_texts() if texts is None else texts
    out = []
    for name, text in texts.items():
        for line_no, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if not stripped:
                continue
            m = TECH_VERSION_RE.search(line)
            if m:
                out.append(f"{name}:{line_no}: technology version {m.group(0)!r}")
            if USING_RE.search(line) and stripped not in ALLOWED_USING:
                out.append(f"{name}:{line_no}: USING without a recorded reason")
    return out


def test_no_inventory_in_the_adl():
    problems = inventory_problems()
    assert problems == [], "\n".join(problems)


def test_version_detector_fires():
    texts = {"x.adl": 'DEFINE CONST V AS "Flink 2.2"'}
    assert inventory_problems(texts) == ["x.adl:1: technology version 'Flink 2.2'"]


def test_using_detector_fires():
    texts = {"x.adl": "DEFINE AGENT A AS a USING LANGUAGE Python"}
    assert inventory_problems(texts) == ["x.adl:1: USING without a recorded reason"]
