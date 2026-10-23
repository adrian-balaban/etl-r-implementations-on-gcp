"""Batching for the big BigQuery tables: a few LARGE batches, never hundreds of small ones.

On real BigQuery `FileLoadsBigQueryWriter` turns every batch of >= `min_load_rows` rows into
its own *load job*, and a load job is a table update operation. The pipelines used to batch
with `beam.BatchElements(min_batch_size=100, max_batch_size=500)`, so at 200,000 rows every
full batch became a load job: ~400 jobs against one table within minutes, which BigQuery
refuses (`429 Exceeded rate limits: too many table update operations for this table`, found
on the 2026-10-17 real-GCP run, after the 50-record fixture had passed). Small inputs never
reach the threshold, so nothing local, and nothing at fixture size, could show it.

`LoadBatches` keys rows over a handful of shards, groups each shard with a plain `GroupByKey`
and cuts the grouped rows into batches of up to `max_batch_size`: 200,000 rows become about 4-8
load jobs per table. It deliberately avoids `beam.GroupIntoBatches`, a stateful DoFn that the
local DirectRunner (which the emulator lane runs on) cannot evaluate; `GroupByKey` behaves the
same on every runner, and on Dataflow the grouped values are a lazy iterable, so only one batch
is held in memory at a time. Inputs below the writer's
threshold still take the `insertAll` path exactly as before (a 52-row run is at most `shards`
batches of a dozen rows).

This module imports Beam, unlike the rest of `pipelines/common`, so it is kept apart from the
engine modules that must stay Beam-free.
"""
from __future__ import annotations

import itertools
import random
from typing import Iterable, Iterator

import apache_beam as beam


class LoadBatches(beam.PTransform):
    """PCollection[row] -> PCollection[list[row]], in few large batches."""

    def __init__(self, max_batch_size: int = 50_000, shards: int = 4) -> None:
        super().__init__()
        if max_batch_size < 1 or shards < 1:
            raise ValueError("max_batch_size and shards must be >= 1")
        self.max_batch_size = max_batch_size
        self.shards = shards

    def expand(self, pcoll):
        shards, size = self.shards, self.max_batch_size

        def cut(keyed: tuple[int, Iterable[dict]]) -> Iterator[list]:
            rows = iter(keyed[1])
            while batch := list(itertools.islice(rows, size)):
                yield batch

        return (
            pcoll
            | "Shard" >> beam.WithKeys(lambda _row, n=shards: random.randrange(n))
            | "Group" >> beam.GroupByKey()
            | "Cut" >> beam.FlatMap(cut)
        )
