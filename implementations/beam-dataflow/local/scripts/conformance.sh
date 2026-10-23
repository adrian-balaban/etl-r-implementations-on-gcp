#!/usr/bin/env bash
# Run the oracle input through the Beam lane (local stack) and check it against
# spec/fixtures/expected.   Normally invoked as `make conformance`.
#
# The input is `harness.generate --accounts 50` (default seed), which spec/conformance's tests
# pin to be byte-identical to spec/fixtures/ACCOUNT.src - the file the oracle was produced from.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

PY="${PY:-.venv/bin/python}"
RUN_ID="run-conf-beam-$(date +%Y%m%d%H%M%S)"
[[ "$RUN_ID" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "bad run id: $RUN_ID" >&2; exit 2; }
OUT="work/conformance/beam"
rm -rf "$OUT"; mkdir -p "$OUT"

echo "▶ beam lane, run ${RUN_ID} (50 accounts + 5 seeded defects = the 55-record fixture)"
"$PY" local/scripts/run_pipeline.py --accounts 50 --run-id "$RUN_ID"

echo "▶ dumping the seven tables for ${RUN_ID} -> ${OUT}"
"$PY" local/scripts/dump_tables.py --run-id "$RUN_ID" --out "$OUT"

echo "▶ comparing against spec/fixtures/expected (profile: staged)"
python3 -I ../../spec/conformance/compare.py "$OUT" --profile staged
