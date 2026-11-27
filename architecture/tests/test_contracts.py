"""[K02-1] The artefact names derive from Contracts — proven three ways:

1. the constants in architecture/adl/contracts.adl are those in spec/contracts/artefacts.json
   (the ADL never drifts from the single source);
2. the Python reader (beam pipelines/common/artefacts.py, stdlib-only) loads the same file
   and derives the same names at runtime — "loaded, not restated";
3. the oracle respects the shapes: record_lineage.tsv contains 'raw:<hash16>' (CONTENT_NAME)
   and the account's sha256-32 key (ACCOUNT_KEY), pinned in the fixture manifest.
"""
import importlib.util
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ADL_CONTRACTS = REPO / "architecture" / "adl" / "contracts.adl"
ARTEFACTS_JSON = REPO / "spec" / "contracts" / "artefacts.json"
BEAM_ARTEFACTS = REPO / "implementations" / "beam-dataflow" / "pipelines" / "common" / "artefacts.py"
FIXTURE_MANIFEST = json.loads((REPO / "spec" / "fixtures" / "manifest.json").read_text(encoding="utf-8"))
EXPECTED_LINEAGE = (REPO / "spec" / "fixtures" / "expected" / "record_lineage.tsv").read_text(encoding="utf-8")

CONST_RE = re.compile(r'^\s*DEFINE CONST (\w+) (?:AS )?"?([^"\n]+)"?\s*$', re.M)


def adl_consts(text: str) -> dict[str, str]:
    return dict(CONST_RE.findall(text))


def const_problems(consts: dict, artefacts: dict) -> list[str]:
    checks = [
        ("ARTEFACT_DAT_NAME", artefacts["artefacts"]["dat"]),
        ("ARTEFACT_CHS_NAME", artefacts["artefacts"]["chs"]),
        ("RUN_ID", artefacts["columns"]["src"]["run_id"]),
        ("ACCOUNT_KEY", artefacts["columns"]["src"]["account_key"]),
    ]
    return [f"{k}: ADL={consts.get(k)!r} != artefacts.json={exp!r}"
            for k, exp in checks if consts.get(k) != exp]


def test_adl_constants_match_artefacts_json():
    artefacts = json.loads(ARTEFACTS_JSON.read_text(encoding="utf-8"))
    consts = adl_consts(ADL_CONTRACTS.read_text(encoding="utf-8"))
    assert consts["ARTEFACT_DAT"] in artefacts["artefacts"]["dat"]
    assert consts["ARTEFACT_CHS"] in artefacts["artefacts"]["chs"]
    assert const_problems(consts, artefacts) == []


def _beam_artefacts():
    spec = importlib.util.spec_from_file_location("beam_artefacts", BEAM_ARTEFACTS)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_python_reader_derives_the_same_names():
    consts = adl_consts(ADL_CONTRACTS.read_text(encoding="utf-8"))
    artefacts = json.loads(ARTEFACTS_JSON.read_text(encoding="utf-8"))
    mod = _beam_artefacts()
    assert mod.manifest() == artefacts
    sequence = f"{1:0{artefacts['dat_sequence_width']}d}"
    assert mod.dat("ACCOUNT", 1) == consts["ARTEFACT_DAT_NAME"].format(record="ACCOUNT", sequence=sequence)
    assert mod.dat("ACCOUNT", 1) == "ACCOUNT.001.DAT"
    assert mod.artefact_names("ACCOUNT")["chs"] == consts["ARTEFACT_CHS_NAME"].format(record="ACCOUNT")
    assert mod.columns("src")["run_id"] == consts["RUN_ID"]
    assert mod.columns("src")["account_key"] == consts["ACCOUNT_KEY"]


def test_oracle_uses_the_contract_shapes():
    raw = "raw:" + FIXTURE_MANIFEST["known_keys"]["raw_sha256_16_of_truncated_row"]
    account_key = FIXTURE_MANIFEST["known_keys"]["account_key_sha256_32_of_ACC000900003"]
    assert raw in EXPECTED_LINEAGE, "CONTENT_NAME does not show up in the oracle"
    assert account_key in EXPECTED_LINEAGE, "ACCOUNT_KEY does not show up in the oracle"


def test_the_mismatch_checker_fires_on_a_doctored_adl():
    artefacts = json.loads(ARTEFACTS_JSON.read_text(encoding="utf-8"))
    doctored = ADL_CONTRACTS.read_text(encoding="utf-8").replace(
        '"{record}.{sequence}.DAT"', '"{record}-BAD.DAT"')
    problems = const_problems(adl_consts(doctored), artefacts)
    assert problems, "the checker must catch an ADL that derives wrongly"
    assert any("ARTEFACT_DAT_NAME" in p for p in problems)