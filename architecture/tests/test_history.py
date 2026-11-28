"""[K02-4 K15-1 K18-2 K18-3 K18-5] The history checks: churn, scope, authority, agent conflicts.

Deterministic reads of git over a fixed window (30 days). They run inside `adl-test` and, with
a full clone, weekly in `.github/workflows/history.yml` (G6). A shallow clone sees less history,
so it can only pass — never falsely fail. Each check is a pure function with its own failing case.
"""
from __future__ import annotations

import re
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MAX_SHARED_CHANGES = 2
WINDOW_DAYS = 30
GOVERNANCE_ADL = REPO / "architecture" / "adl" / "governance.adl"
MAPPING_RE = re.compile(r"^spec/contracts/mappings/.+\.ya?ml$")


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True).stdout


def commits_in_window(days: int = WINDOW_DAYS) -> list[tuple[str, str, str]]:
    since = (datetime.now(timezone.utc) - timedelta(days=days)).strftime("%Y-%m-%d")
    rows = git("log", f"--since={since}", "--format=%H%x09%an%x09%ae").splitlines()
    return [tuple(r.split("\t")) for r in rows if r.count("\t") == 2]


def change_set(sha: str) -> list[str]:
    return [f for f in git("show", "--name-only", "--format=", sha).splitlines() if f]


def added_set(sha: str) -> list[str]:
    return [f for f in git("show", "--diff-filter=A", "--name-only", "--format=", sha).splitlines() if f]


def churn_violations(changes: dict[str, list[str]], budget: int = MAX_SHARED_CHANGES) -> list[str]:
    touched = [sha for sha, files in changes.items() if any(f.startswith("spec/contracts/") for f in files)]
    return [f"{len(touched)} commits touched spec/contracts/ in the window (budget {budget})"] if len(touched) > budget else []


def scope_violations(changes: dict[str, list[str]], added: dict[str, list[str]]) -> list[str]:
    out = []
    for sha, files in changes.items():
        if any(MAPPING_RE.match(f) for f in added.get(sha, [])):
            outside = [f for f in files if not f.startswith("spec/contracts/")]
            if outside:
                out.append(f"{sha[:8]}: adds a mapping but also touches {outside}")
    return out


def declared_members(text: str) -> tuple[set[str], set[str]]:
    emails = set(re.findall(r"DEFINE PERSON .+? AS (\S+@\S+)", text))
    agents = set(re.findall(r"DEFINE AGENT (.+?) AS ", text))
    return emails, agents


def authority_violations(commits: list[tuple[str, str, str]],
                         emails: set[str], agents: set[str]) -> list[str]:
    out = []
    for sha, name, email in commits:
        if email in emails or name in agents:
            continue
        out.append(f"{sha[:8]}: {name} <{email}> is not a declared person or agent")
    return out


def agent_conflict_problems(merges: list[tuple[str, str, str]]) -> list[str]:
    out = []
    for sha, author, body in merges:
        if "co-authored-by:" in body.lower():
            out.append(f"{sha[:8]}: a merge by {author} carries an agent trailer — record its disposition")
    return out


def merges_in_window(days: int = WINDOW_DAYS) -> list[tuple[str, str, str]]:
    since = (datetime.now(timezone.utc) - timedelta(days=days)).strftime("%Y-%m-%d")
    raw = git("log", "--merges", f"--since={since}", "--format=%H%x09%an%x09%B%x00")
    out = []
    for chunk in raw.split("\x00"):
        parts = chunk.strip("\n").split("\t", 2)
        if len(parts) == 3:
            out.append((parts[0], parts[1], parts[2]))
    return out


def test_shared_contract_churn_is_within_budget():
    window = commits_in_window()
    changes = {sha: change_set(sha) for sha, _, _ in window}
    problems = churn_violations(changes)
    assert problems == [], "\n".join(problems)


def test_a_new_mapping_touches_only_contracts():
    window = commits_in_window()
    changes = {sha: change_set(sha) for sha, _, _ in window}
    added = {sha: added_set(sha) for sha, _, _ in window}
    problems = scope_violations(changes, added)
    assert problems == [], "\n".join(problems)


def test_commits_come_from_declared_people_and_agents():
    emails, agents = declared_members(GOVERNANCE_ADL.read_text(encoding="utf-8"))
    problems = authority_violations(commits_in_window(), emails, agents)
    assert problems == [], "\n".join(problems)


def test_agent_merges_have_a_disposition():
    problems = agent_conflict_problems(merges_in_window())
    assert problems == [], "\n".join(problems)


def test_scope_detector_fires_on_a_mapping_commit_that_touches_code():
    changes = {"abc12345": ["spec/contracts/mappings/m.yaml", "implementations/flink/gate/Gate.java"]}
    added = {"abc12345": ["spec/contracts/mappings/m.yaml"]}
    assert scope_violations(changes, added) == [
        "abc12345: adds a mapping but also touches ['implementations/flink/gate/Gate.java']"
    ]


def test_authority_detector_fires_on_an_undeclared_author():
    problems = authority_violations([("abc12345", "Mallory", "m@example.com")], {"a@b.c"}, {"Cline"})
    assert problems == ["abc12345: Mallory <m@example.com> is not a declared person or agent"]


def test_churn_detector_fires_above_budget():
    changes = {f"sha{i}": ["spec/contracts/artefacts.json"] for i in range(3)}
    assert churn_violations(changes) == ["3 commits touched spec/contracts/ in the window (budget 2)"]
