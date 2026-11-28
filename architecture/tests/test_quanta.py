"""[K10-1 K08-4] Every Terraform store is claimed by its quantum.

static-coupling.adl binds each quantum to its physical stores; this harness reads that file,
then scans each lane's Terraform: every Cloud SQL instance, every managed Kafka cluster and
every BigQuery dataset needs the declaration that owns it.
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ADL = REPO / "architecture" / "adl" / "static-coupling.adl"
QUANTUM_LANE = {
    "Flink Quantum": "flink",
    "FlinkJdbc Quantum": "flink-jdbc",
    "Beam Quantum": "beam-dataflow",
}
Q_RE = re.compile(r"^\s*DEFINE QUANTUM (.+?)\s*$")
BIND_RE = re.compile(r"^\s*DEFINE (DATABASE|BROKER) .+? AS (.+?)\s*$")
INSTANCE_RE = re.compile(r'instance_name\s*=\s*"([^"]+)"')
VAR_DEFAULT_RE = re.compile(r'variable\s+"instance_name"\s*\{[^}]*?default\s*=\s*"([^"]+)"', re.S)


def quantum_bindings(text: str) -> dict[str, dict[str, set[str]]]:
    out: dict[str, dict[str, set[str]]] = {}
    current: str | None = None
    for line in text.splitlines():
        q = Q_RE.match(line)
        if q:
            current = q.group(1)
            out.setdefault(current, {"DATABASE": set(), "BROKER": set()})
            continue
        bind = BIND_RE.match(line)
        if current and bind:
            out[current][bind.group(1)].add(bind.group(2).strip())
    return out


def terraform_text(root: Path, lane: str) -> str:
    base = root / "implementations" / lane / "terraform"
    if not base.exists():
        return ""
    return "\n".join(p.read_text(encoding="utf-8", errors="replace") for p in sorted(base.rglob("*.tf")))


def instances(text: str) -> set[str]:
    found = set(INSTANCE_RE.findall(text)) | set(VAR_DEFAULT_RE.findall(text))
    return {name.split("${")[0] for name in found}


def quanta_problems(root: Path = REPO, adl_text: str | None = None) -> list[str]:
    adl = adl_text if adl_text is not None else (root / "architecture" / "adl" / "static-coupling.adl").read_text(encoding="utf-8")
    bindings = quantum_bindings(adl)
    out = []
    for quantum, lane in QUANTUM_LANE.items():
        claimed = bindings.get(quantum, {"DATABASE": set(), "BROKER": set()})
        text = terraform_text(root, lane)
        for instance in sorted(instances(text)):
            if instance not in claimed["DATABASE"]:
                out.append(f"{lane}: Cloud SQL instance {instance!r} is not claimed by {quantum}")
        if 'resource "google_managed_kafka_cluster"' in text and not claimed["BROKER"]:
            out.append(f"{lane}: a managed Kafka cluster with no BROKER declared for {quantum}")
        if 'resource "google_bigquery_dataset"' in text and not any(
            "BigQuery" in d for d in claimed["DATABASE"]
        ):
            out.append(f"{lane}: BigQuery datasets with no BigQuery binding for {quantum}")
    return out


def test_every_terraform_store_is_claimed():
    problems = quanta_problems()
    assert problems == [], "\n".join(problems)


def test_detector_fires_on_an_unclaimed_instance(tmp_path):
    root = tmp_path / "r"
    (root / "architecture" / "adl").mkdir(parents=True)
    (root / "architecture" / "adl" / "static-coupling.adl").write_text(
        "DEFINE QUANTUM Flink Quantum\n"
        "     DEFINE DATABASE account_source_db AS mig-flink-pg\n",
        encoding="utf-8",
    )
    tf = root / "implementations" / "flink" / "terraform"
    tf.mkdir(parents=True)
    (tf / "main.tf").write_text('module "x" {\n  instance_name = "stray-pg"\n}\n', encoding="utf-8")
    assert quanta_problems(root) == [
        "flink: Cloud SQL instance 'stray-pg' is not claimed by Flink Quantum"
    ]
