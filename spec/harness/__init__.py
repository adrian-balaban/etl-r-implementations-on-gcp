"""Synthetic Source System data harness — stands in for the Source System.

Lets the whole chain run end-to-end without the other team's real data delivery, and
deliberately seeds excluded, malformed and duplicate records so the reject path and the
balancing equation are exercised with non-zero values.

Generation is seeded and deterministic, so the manifest's expected counts are exact — the
excluded count must match *exactly*, not approximately.
"""
