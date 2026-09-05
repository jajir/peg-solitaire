# Forecast range sharding: measured results

Measured on 2026-09-05. Range forecasting is now used by `RoundEnumerator`.
Zstd level 3, long delta-varint pages, and all 49 board bits are unchanged.

## Whole-index results

These are complete, exact Senku frontiers, not synthetic random keys or a
sample projected into an estimated index size. Size includes every regular
file in the completed index plus the new round's sample sidecar, where present.
MB means 1,000,000 bytes; these are logical file lengths, not filesystem
allocated blocks or peak temporary storage.

| Destination round | Unique states | Prefix-hash bytes | Forecast-range bytes | Space saved | Median prefix time | Median forecast time |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 11 | 1,523,417 | 3,282,386 | 2,462,473 | 25.0% | 2.953 s | 1.046 s |
| 12 | 6,151,839 | 9,917,928 | 7,017,452 | 29.2% | 22.001 s | 3.886 s |

For round 12, whole-index density improves from **1.612 to 1.141 bytes/state**,
or **0.620 to 0.877 states/byte**. The new figure is about **9.13 bits/state**,
including file overhead and the 24,068-byte destination sample.

The measured round-12 runtime improvement is **5.66×**. This is the combined
routing change, not a claim that Zstd got 5.66× faster: the new router also
mixes the full key before reserving seven hash bits for the range ID. The
previous router hashed only the top 25 board bits, so different states sharing
a prefix had exactly the same hash in HestiaStore's ingestion maps. Independent
mixing inside the engine cannot recover entropy already discarded by that
callback. No hash-only ablation was performed, so the speedup is not apportioned
between hash-table behavior, locality, and maintenance work.

## Payload density and balance

This comparison encodes each shard using the actual HestiaStore delta-varint
writer and Zstd-3 filter. Every compressed page is decompressed and checked
key-for-key. Payload figures exclude chunk headers, 8 KiB file padding,
manifests and sample files; they are not total disk-size figures.

| Round | States | Prefix payload bytes | Forecast payload bytes | Forecast bytes/state | Busiest/average shard, prefix → forecast |
| --- | ---: | ---: | ---: | ---: | ---: |
| 9 | 76,830 | 195,905 | 158,342 | 2.061 | 3.137 → 1.171 |
| 10 | 353,018 | 741,408 | 567,092 | 1.606 | 1.882 → 1.228 |
| 11 | 1,523,417 | 2,731,394 | 1,963,983 | 1.289 | 1.522 → 1.259 |
| 12 | 6,151,839 | 9,326,454 | 6,400,281 | 1.040 | 1.331 → 1.169 |

Round 12 saves **31.4% of compressed payload**, reaching **0.961 states/byte**.
Raw delta-varint bytes only fall from 16,554,090 to 16,207,358; much of the
compressed gain comes from grouping local patterns together, not just making
individual varints shorter. All four measured frontiers occupy all 128 shards.

The forecast uses only the *preceding* round's bounded sample. No destination
keys are used to choose boundaries. Destination round 11 uses 2,758 samples
from round 10; destination round 12 uses **2,976 samples from round 11**.
Warmed forecast construction took about **3.9 ms**
for destination 12. The sampler stores at most 4,096 keys and doubles its
sampling stride when full, so the retained count need not be exactly 4,096.

## Method and limitations

- Host: Apple M4, 10 logical CPUs, 16 GiB RAM, macOS 26.5.2; Homebrew OpenJDK
  25.0.2, `-Xms2g -Xmx2g`, Zstd JNI 1.5.7-16. Benchmark files were created
  under `/tmp` on the local filesystem, not on `/Volumes/ponrava`.
- Baseline and candidate use the same installed HestiaStore engine and the
  same application storage settings: 128 shards, 8 processing workers, queue
  32, 8 maintenance threads, maintenance queue 120, merge fan-in 64, 10 million
  in-memory entries, 1 million keys/page, 10 million entries/part, 8 KiB disk
  buffer. Only routing and destination-side sample publication differ.
- Source states are loaded into memory *before* timing. The timed path includes
  range forecasting, index creation, real move generation/canonicalization,
  parallel ingestion, `finishWriting()`, reading and checking every output key,
  and candidate sample publication. Both modes collect a destination sample
  during verification, but only the candidate writes it. Thus this measures
  the round's generation/write/verification path, **not full CLI wall time or
  source-index disk reading**. Loading/generating the corpus and filesystem
  size enumeration are outside timing.
- Round 11 processes 353,018 source states and 5,580,485 moves; round 12 processes
  1,523,417 source states and 25,004,471 moves. Exact expected outputs come from
  a separate primitive-array, sort-and-deduplicate enumeration. Each timed
  result is checked against that complete sorted frontier, including its count.
- Four pairs per round were run sequentially in one JVM, alternating mode order.
  Pair 1 is excluded as warm-up; reported medians use pairs 2–4. Round-12
  observed ranges are 21.966–22.092 s for prefix and 3.834–3.921 s for forecast.
  This is a local regression measurement, not a throughput guarantee.
- These frontiers fit within one active ingestion batch. Unit/integration tests
  separately cover multiple flushes and duplicate merges, but the benchmark
  does not establish large-run, multi-batch throughput or peak disk usage.
- Small rounds can be slightly **larger** overall: at round 9, both modes use
  1,048,576 bytes of padded part files despite their different payload sizes.
  With metadata and the sidecar, totals were 1,070,321 vs 1,089,556 bytes
  (+1.8%). Fewer shards or smaller file blocks would be a separate optimization.
- Later-round distributions may differ. Forecast boundaries are an approximate
  balance heuristic, not a guarantee. No completed user index was rewritten and
  no production run was started or restarted for these tests.

## Separate maintenance latency found during validation

A deliberately tiny-buffer test (128 in-memory entries, merge fan-in 2,
1,024 distinct keys inserted twice) took about **46 seconds**, even using
`MemDirectory`. A thread dump showed the caller waiting in
`SenkuWritingRuntime.awaitMaintenanceFinished()`, both maintenance workers
idle, and the coordinator waiting for its next scheduled scan. The current
engine scans every **three seconds**; `completionProcessed()` checks whether
draining is complete but does not immediately schedule the next merge wave.

This is a separate, existing scheduling latency, not compression CPU or disk
throughput. Multi-wave finalization can accumulate repeated three-second waits.
The application uses fan-in 64, so the stress test exaggerates how frequently
the delay is encountered. The regression test now uses that same
fan-in while still forcing multiple flushes and cross-flush duplicates. The
engine scheduler was left unchanged in this routing-focused change. An
event-driven continuation of the final drain is a worthwhile follow-up,
with its own lifecycle/concurrency tests and multi-batch benchmark.

## Individual timed trials

Each row is one sequential pair. Pair 1 is retained here for transparency but
excluded from the summary medians. All 16 trials passed exact-key verification.

| Round | Pair | First mode | Prefix total ms | Forecast total ms | Prefix ingest ms | Forecast ingest ms |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| 11 | 1 (warm-up) | prefix | 3566.054 | 1046.659 | 2874.934 | 636.444 |
| 11 | 2 | forecast | 2953.141 | 1046.365 | 2635.530 | 607.324 |
| 11 | 3 | prefix | 2890.374 | 1054.887 | 2601.740 | 617.519 |
| 11 | 4 | forecast | 2954.918 | 945.388 | 2629.567 | 602.738 |
| 12 | 1 (warm-up) | prefix | 22592.037 | 3733.917 | 21133.340 | 2585.085 |
| 12 | 2 | forecast | 22092.422 | 3920.978 | 21171.442 | 2573.013 |
| 12 | 3 | prefix | 21966.465 | 3834.132 | 21063.535 | 2384.712 |
| 12 | 4 | forecast | 22001.111 | 3885.668 | 21018.696 | 2307.213 |

## Reproduce

Build against the sibling HestiaStore version providing the codec API, then:

```sh
mvn clean verify
BENCH_JAVA=/opt/homebrew/opt/openjdk/bin/java
BENCH_CP=target/test-classes:target/peg-solitaire-jar-with-dependencies.jar
BENCH_MAIN=cz.coroptis.pegsolitaire.RangeShardingBenchmark
BENCH_DIR=$(mktemp -d /tmp/senku-range-benchmark.XXXXXX)

"$BENCH_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$BENCH_CP" \
  "$BENCH_MAIN" generate "$BENCH_DIR/corpus" 12

for benchmark_round in 9 10 11 12; do
  "$BENCH_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$BENCH_CP" \
    "$BENCH_MAIN" density "$BENCH_DIR/corpus" "$benchmark_round"
done

for benchmark_round in 11 12; do
  "$BENCH_JAVA" --enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g -cp "$BENCH_CP" \
    "$BENCH_MAIN" run "$BENCH_DIR/corpus" "$benchmark_round" "$BENCH_DIR/indexes" 4
done
```

`generate` refuses to overwrite existing corpus files. `run` creates fresh,
uniquely named index directories and retains them for inspection. No benchmark
command deletes data. `forecast_ms`, `ingest_ms`, `finish_ms` and `total_ms` in
its output are cumulative elapsed times from the start of a trial. A trial's
`verified=true` means all persisted output keys matched the exact corpus.

The automated suite includes sample bounds/order/immutability, metadata CRC
and format validation, atomic replacement and failed-publication cleanup,
missing-sample backfill, orphan-sidecar retry, board-bit preservation,
range/hash invariants, multiple-flush deduplication, and process-style restarts.
