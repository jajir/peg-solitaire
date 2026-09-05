# Board computation, batching and board-aware compression

The three optimizations are implemented: a compiled move/symmetry kernel,
bounded batching with exact recent-key filtering, and board-aware rank pages.
The integrated measurement below shows the space/CPU tradeoff: the complete
ranked index is 34.9% smaller, while the measured round path improves modestly.
The faster computation with ordinary delta pages is faster still.

## Measured results — 2026-09-05

Apple M4, 10 logical CPUs, 16 GiB RAM, macOS 26.5.2, OpenJDK 25.0.2; local
`/tmp` filesystem, eight workers, fresh 2 GiB JVMs, one warm-up followed by one
measured trial per child, three alternating-order trials per mode. Engine tests
and JMH were idle during measured application trials. Other desktop processes
were not disabled. All 24 warm-up/measured indexes contained exactly the
expected 6,151,839 keys, checked both immediately and after reopening.

| Mode | Median round path | All three trials (s) | Complete index + sample | Bytes/state |
| --- | ---: | --- | ---: | ---: |
| Baseline forecast ranges | 3.698 s | 3.626 / 4.085 / 3.698 | 7,017,452 B | 1.140708 |
| Kernel + batching | 2.709 s | 2.679 / 2.709 / 2.740 | 7,017,452 B | 1.140708 |
| Kernel + batching + exact cache | 2.546 s | 2.546 / 2.485 / 2.593 | 7,017,452 B | 1.140708 |
| All three, including rank pages | 3.440 s | 3.440 / 3.486 / 3.432 | 4,568,208 B | 0.742576 |

The full configuration reduces this measured round-path median by 7.0% and
complete logical file size by 34.9%. Keeping numeric delta pages with the
optimized processor reduces round-path time by 31.1%. These are not claims
about external-disk production runs or later, multi-flush frontiers.

The round workload generates 25,004,471 raw moves from 1,523,417 parents in
every mode. Batching reduces executor tasks from 1,523,417 to 372. The exact
cache submits 18,427,167–18,443,972 puts in the measured cached modes, about
26.3% fewer than the baseline. Scheduling changes the cache hit count slightly;
it never changes the persisted state set. Eight caches use 4.0625 MiB of array
payload plus small object headers; queued/active default batch payload is
bounded at about 1.25 MiB plus the caller's current batch. The compiled kernel
retains approximately 154 KiB including estimated object headers.

| Median stage | Baseline | Kernel + batch + cache | Full ranked configuration |
| --- | ---: | ---: | ---: |
| Ingestion | 2,544.052 ms | 1,032.460 ms | 1,028.649 ms |
| Finish writing | 684.304 ms | 1,036.874 ms | 1,503.850 ms |
| First verification/sample scan | 438.095 ms | 448.148 ms | 886.197 ms |

Stage medians need not sum to the median total. Faster ingestion changes when
finalization/GC work is paid. Ranking more than doubles the first read/verify
stage here. Including the **additional** reopen verification, benchmark total
medians are 4.153 s baseline, 3.138 s kernel+batch, 2.938 s kernel+batch+cache,
and 4.323 s full rank. All three total-time trials respectively were
4.071/4.563/4.153, 3.055/3.138/3.173, 2.938/2.894/3.036, and
4.323/4.355/4.313 seconds. Thus that extra-read workload is 4.1% slower with
ranking. Source-index reads are excluded from both timing definitions, so do
not infer whole-production-round speed from the 7.0% improvement alone.

| Codec | Encoded bytes before Zstd | Compressed payload | Payload bytes/state | States/payload byte |
| --- | ---: | ---: | ---: | ---: |
| Numeric delta/Zstd-3 | 16,207,358 | 6,400,281 | 1.040385 | 0.961183 |
| Population/parity rank delta/Zstd-3 | 6,897,585 | 3,960,786 | 0.643838 | 1.553186 |

Payload drops 38.1%. Complete index density is 1.347 states per byte, including
chunk headers, padding, metadata, and the 24,068-byte sidecar. The ranked
index itself is 4,544,140 bytes; the baseline index itself is 6,993,384 bytes.

The canonical HestiaStore `senku-fixed-weight-compression` JMH profile
separately merged 100,000 identical synthetic fixed-weight keys, two forks and
eight measured iterations, with the GC profiler. Numeric delta/Zstd-3 measured
1.688 ± 0.064 ms/op and 1,169,523 B/op; rank delta/Zstd-3 measured
11.923 ± 0.289 ms/op and 787,270 B/op. That isolated CPU pipeline is 7.06×
slower but allocates 32.7% less. It is a synthetic merge, not a solver frontier.
Large multi-flush production runs may pay this conversion cost repeatedly.

An independent kernel-only probe also compared every one of the 25,004,471
children with the simple board/symmetry oracle before timing. Five alternating
single-thread trials had medians 1,102.054 ms reference and 140.375 ms compiled
(7.85×). This excludes scheduling, storage, filtering, and compression and is
not the application's overall speedup.

### Verification at the time of the optimization measurements

The application passes 129 tests, including all-board move-oracle equivalence,
full early frontiers, raw-word-crossing jumps, concurrent kernel use, exact-cache
collisions/zero/failure/isolation, interruption cleanup, persisted rank round
reopen, and advancing an older delta round without modifying it. Tests also
prove D4 closure of the parity row space for all populations on all three boards.

HestiaStore passes 2,433 unit tests, all three new rank integration tests,
and 107 tests in the remaining modules. The new rank algorithm has 100%
instruction coverage. Documentation navigation, strict MkDocs, formatting and
diff checks pass.

**The full HestiaStore verification was not green at measurement time.** In both full runs,
`SenkuIndexConcurrencyIT.maintenanceMayOverlapCallerIngestion` failed with
`Invalid ready flush directory layout`. The same method passed two isolated
reruns. The suspected existing race is finalization consulting a cached
catalog after an older merge completes, before a newly published final flush
is rescanned. `SenkuWritingRuntime.class` is byte-identical in the immediate
baseline and current engine (SHA-256
`d2e22c9ec6efdad15c602dd6b5fb0bafdad7b09983ca152532897e5a57da46b8`).
The failing test uses the existing non-ranked concurrent-ingestion path.
No scheduler/ingestion race fix is included in these three optimizations.
The round-12 benchmark has fewer unique keys than the 10-million-entry memory
threshold and does not establish safety of later multi-flush production runs.
This race required a separate lifecycle fix; the measurements above describe
the preserved pre-fix jars, not that later fix.
No live solver was started/restarted and no historical data was changed.

### Follow-up: finalization race fixed — 2026-09-05

The race was confirmed in HestiaStore's `SenkuWritingRuntime`, not Segment
Index or the board codec. An older merge completion could empty the cached
flush catalog after ingestion had committed another flush, then publish
`ready.properties` before the queued final scan discovered that flush.
This also occurred when `finishWriting()` itself had no tail left to flush.

Completion callbacks now request an immediate coordinator tick only while
`FINISHING`. They cannot publish readiness directly. The tick observes
`FINISHING`, rescans committed sources, schedules remaining merges, checks
failure, and only then decides whether finalization is complete. Ordinary
`WRITING` keeps its existing three-second scan interval. This fix changes no
on-disk format and does not repair an index already left invalid by the bug.

Twelve deterministic filesystem integration cases cover both completion
orderings, pre-published versus final-tail flushes, one and three shards,
remaining-merge failure, and empty finalization. Six cases failed against the
old runtime by detecting premature readiness; all twelve pass with the fix.
The tests also require finalization to progress through queued work alone,
without another periodic scan, compare exact keys/values, reopen publicly,
and check lock/executor cleanup. Existing real-executor concurrency tests pass.

The complete post-fix HestiaStore `mvn clean verify -DskipTests=false` is green:
2,434 engine unit tests, 75 passing integration cases with one existing skip,
and 107 tests in the other modules. The fixed engine was installed locally;
the rebuilt application passes all 129 tests. Navigation and strict MkDocs
checks pass, and the lifecycle diagram source and PNG were updated together.
The rebuilt application contains the same fixed runtime class as the engine.
No live solver was started/restarted, no stored rounds were changed, and no
commit or push was performed as part of this fix.

Post-fix application jar SHA-256:
`fd68bed2141529602f6e3adef3a2156e4c07a9f200895462990711ca1a350218`.
Correctness logs and before/after benchmark artifacts are retained in
`/tmp/senku-race-fix.VLi5X6/`. The original optimization measurements above
remain measurements of the earlier preserved jars.

#### Finalization benchmark comparison

The unchanged canonical HestiaStore `senku-index-baseline` profile ran once
against each preserved benchmark jar: one fork, one 200 ms warm-up, three
200 ms measurements, and the GC profiler on the same OpenJDK 25 workstation.
Builds and other benchmarks were idle during both runs. This profile uses
`MemDirectory`, eight shards, two maintenance workers, and four queued jobs;
these are tiny synthetic indexes, not disk-backed solver frontiers.

| Workload | Before | After |
| --- | ---: | ---: |
| Finish seven flushes to L0 | 3,010.055 ms | 0.797 ms |
| Finish recursive run merges | 15,054.160 ms | 2.001 ms |
| Ingest 256 entries through first sorted result, four data shapes | 3,012.868–3,017.857 ms | 0.988–1.271 ms |

The large difference removes periodic-scan waits between waves of finishing
work. It is not a thousands-fold improvement in merge computation or whole
solver speed. The short profile has very wide confidence intervals and cannot
establish small changes: the ingestion means of 10.852 versus 11.730 million
puts/s are too noisy to claim an ingestion improvement. Its normalized
ingestion allocation stays essentially flat at 661.522 versus 661.317 B/put.

The canonical ready-stream mean initially increased 9.7%, with overlapping
very wide errors. A separate, noncanonical focused check therefore repeated
only the 4,096-key ready stream with the same jars/JVM settings, two forks,
three 500 ms warm-ups and five 500 ms measurements per fork. It measured
926.355 ± 9.816 microseconds before and 924.969 ± 17.315 microseconds after
(JMH 99.9% confidence errors): no material read regression was observed.
Normalized allocation changed only 0.27%, with overlapping errors. Raw data
and logs are in `ready-stream-check/` under the fix artifact directory.

There is an allocation tradeoff. The GC profiler reports 1.69 MB/op versus
2.57 MB/op for flush-to-L0, and 3.33 MB/op versus 7.59 MB/op for recursive
merging; end-to-end cases increase 37–57%. These are whole-fixture normalized
allocations, not peak memory or an isolated measurement of metadata scanning.
Additional reconciliation is a plausible contributor. Avoid inferring disk
I/O cost or large-frontier allocation directly from these tiny in-memory cases.

Raw results are in `jmh-before/` and `jmh-after/` under the fix artifact
directory. `comparison/comparison.md` matches every method and parameter set;
it does not rely on the existing summary comparator, which loses parameter
identity and uses the wrong improvement direction for latency. Among packaged
`org/hestiastore/index` classes, only `SenkuWritingRuntime.class` differs
between the compared jars.

Benchmark jar SHA-256 values:

- Before: `46739951685d2c660aebb9175bb8f8176a92c4c3e38802badd02ce11948b9207`.
- After: `31a967e2bcca6ff4724a24439f8bf5abe5a0fc36f8efe1ede5ef8b1d035283ca`.

### Evidence retained locally

- Raw application trials: `/tmp/senku-implementation.pXaf86/rounds.log` and `rounds/`.
- Density: `/tmp/senku-implementation.pXaf86/density.log`.
- Canonical JMH raw JSON/log/metadata: `/tmp/senku-implementation.pXaf86/hestia-jmh/`.
- Application tests: `/tmp/senku-implementation.pXaf86/app-verify-installed.log`.
- HestiaStore tests and installation: `/tmp/hestia-rank-smoke.X2CSKz/`.
- Kernel oracle/timing: `/tmp/senku-compiled-kernel.N1AkdF/`.

Baseline jar SHA-256:
`4f740b5cb8d60ae9655943b3a6d64370950cc6004de4081ca3d4a3d5b7f61856`.
Candidate jar SHA-256:
`e891f246a39c439553f55e5fff5d92896cc46432b65a2daf0c3d945bdbf2f9f5`.
Both are preserved in `/tmp/senku-implementation.pXaf86/`. These are dirty-tree
builds based on application commit `f3f7ffb7ba90921fd64a934c1f84f40a153df53e`
and HestiaStore commit `b74ae05b7577da3aa70794af56963681062e1e17`; the baseline
already includes the earlier range-routing and delta/Zstd changes. The new
engine adds rank configuration, conversion, metadata and corresponding reader
plumbing; no scheduler/ingestion optimization is included.

Source corpus SHA-256:
`929573e0e30a30bceec9852aa78235d214723b5fbdb2f7b9311efb0d7d36a9c0`.
Target corpus SHA-256:
`65ec3614c08805cd54345fd933d582d4eeba67da4c975d1f83088e713a6083e8`.

## Comparison

All modes retain forecast numeric ranges, 128 shards, Zstd level 3, eight
processing workers, queue capacity 32, eight maintenance threads, and the same
HestiaStore buffering/page/merge settings. No historical frontier is removed.

| Mode | Board computation | States/task | Exact recent cache/worker | Key page codec |
| --- | --- | ---: | ---: | --- |
| `baseline` | Preserved pre-change application | 1 | None | Long delta-varints |
| `fast-only` | Compiled move and symmetry kernel | 4,096 | None | Long delta-varints |
| `fast-cache` | Compiled kernel | 4,096 | 65,536 full keys | Long delta-varints |
| `full` | Compiled kernel | 4,096 | 65,536 full keys | Population/parity-constrained ranks, delta-varints |

The baseline is the immediately preceding **forecast-range** implementation,
not the older prefix-hash implementation. `fast-only` combines kernel and
batching; it does not isolate their individual contributions. Comparing
`fast-only` with `fast-cache` isolates exact-cache effects in the candidate.
Comparing `fast-cache` with `full` isolates the page representation. Baseline
and candidate jars may contain different engine implementations; record both
revisions/jar hashes and any unrelated engine changes before attributing a
baseline difference exclusively to application changes.

## Workload and validation

The intended primary corpus is destination round 12: all 1,523,417 source
states in `senku-round-11.longs`, generating all 6,151,839 unique target states
in `senku-round-12.longs`. Source and exact expected target arrays are loaded
and validated **before** timing. File format is sorted, unique, big-endian
64-bit board states; the reader also checks board width and peg count.

Only the source sample determines forecast ranges; target keys are never used
to optimize routing. Every persisted key is compared to the complete expected
frontier, in order, first after `finishWriting()` and again after closing and
reopening the index. A successful count or checksum alone is not accepted.
The same application sidecar is written in every mode and counted in storage.

## Timing boundaries

The runner reports independent, noncumulative durations:

- `setup_ms`: range forecasting, codec/kernel preparation and index creation.
- `ingest_ms`: source iteration, generation, canonicalization, task scheduling,
  duplicate filtering and actual persistent-store `put` calls.
- `finish_ms`: `finishWriting()`, including its maintenance drain.
- `verify_ms`: streaming and comparing all keys, collecting the destination
  sample, and closing the first ready handle.
- `sidecar_ms`: writing and atomically publishing the destination sample.
- `round_ms`: the sum of those stages, representing the measured round path.
- `reopen_verify_ms`: additional reopen-and-compare validation, outside the
  normal round path.
- `total_ms`: `round_ms + reopen_verify_ms`.

Corpus loading, filesystem size enumeration, JVM startup and the benchmark
coordinator are excluded. This is not whole-CLI time, source-index disk-read
time, peak RAM, peak temporary disk usage, or a cold-storage benchmark.

Each measured trial runs in a **fresh JVM** with `-Xms2g -Xmx2g`. That child
first performs one complete discarded warm-up of the same mode and workload,
then one measured run. The coordinator alternates mode order between trials
and requires at least three measured trials per mode. Only one benchmark child
runs at a time. Do not run engine tests or other benchmarks concurrently.

The baseline child classpath contains benchmark test classes and **only the
preserved baseline production jar**. Its executed branch uses old APIs, so
it runs the actual old production classes without per-entry reflection.
Candidate children use the candidate production jar instead. Each child
checks and logs the actual jar origin. This also isolates native Zstd loading.

Storage fields count logical file lengths, not allocated filesystem blocks:
`index_bytes` includes all regular index files, `part_bytes` is its `.chunk`
subset, and `total_bytes` additionally includes the sidecar. `bytes_per_state`
uses the complete unique target count. The separate `density` command uses
actual page writers, Zstd-3 compression and exact round-trip readers to report
compressed payload only; payload excludes chunk headers, block padding,
manifests and sidecars and must not be described as whole-index disk size.

## Reproduce

Preserve the pre-change shaded application jar **before rebuilding**. Build
and install the sibling HestiaStore engine containing the new codec, then run
the application's normal verification build. Use test classes plus the shaded
jar on the classpath, without `target/classes`:

```sh
mvn clean verify
BENCH_JAVA=/opt/homebrew/opt/openjdk/bin/java
BENCH_CP=target/test-classes:target/peg-solitaire-jar-with-dependencies.jar
BENCH_MAIN=cz.coroptis.pegsolitaire.RoundOptimizationBenchmark
BENCH_BASELINE=/absolute/path/to/preserved/baseline.jar
BENCH_CORPUS=/absolute/path/to/corpus
BENCH_OUTPUT=$(mktemp -d /tmp/senku-round-optimization.XXXXXX)

"$BENCH_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$BENCH_CP" \
  "$BENCH_MAIN" density "$BENCH_CORPUS" 12

"$BENCH_JAVA" --enable-native-access=ALL-UNNAMED -Xmx2g -cp "$BENCH_CP" \
  "$BENCH_MAIN" run "$BENCH_CORPUS" 12 "$BENCH_BASELINE" "$BENCH_OUTPUT" 3
```

The optional final argument selects modes, e.g. `baseline,full`. The existing
`RangeShardingBenchmark generate <corpus> 12` command can recreate a corpus in
an empty directory. No benchmark command deletes or overwrites existing index
data: each warm-up, measured index and child log has a fresh unique name and
is retained for inspection. Keep raw logs alongside any published summary.
