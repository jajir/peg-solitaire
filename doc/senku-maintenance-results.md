# Senku maintenance discovery and primitive merges

Implemented and validated on 2026-09-06. The implementation changes are in
HestiaStore; peg-solitaire needs the rebuilt dependency, not another application
API or configuration change. Existing uncommitted batching changes were retained.

## Changes and safety boundaries

- `SenkuMetadataDiscovery` reuses parsed immutable flush/run manifests and their
  weighted summaries. Periodic discovery still lists structural directories and
  checks manifest existence, but does not reread known contents. New publications
  are parsed, incomplete sources are not negatively cached, and missing known
  sources fail discovery. Cache updates follow successful reconciliation;
  accepted worker results seed it and successful deletion evicts entries.
- `SenkuWritingRuntime` coalesces completion callbacks into an immediate
  scheduling/backpressure refresh through `SenkuMaintenanceCoordinator.scheduleOnce`.
  Both WRITING and FINISHING refill from the known catalog without another
  discovery scan. Selecting new L0 work still performs its required manifest,
  part-layout and shard-index reads; this is not a zero-I/O scheduler.
- First entry into FINISHING strictly rereads committed metadata after ingestion
  has stopped. A second strict reconciliation precedes READY. A cached WRITING
  view cannot authorize readiness, including when the final active batch is empty.
  External mutation of committed contents during writing is unsupported;
  strict validation detects conflicts in surviving manifests. This is metadata
  validation, not a full audit of all data-page contents.
- `SenkuLongMergeHeap` replaces primitive maintenance's PriorityQueue
  poll/reinsert with cached `long` keys and source ordinals. Advancing the
  selected source replaces the root and repairs downward once. It preserves
  signed ordering, duplicate reduction order, logical callback/sample keys,
  reader cleanup and manifest-last publication. Generic merging is unchanged.

Zstd level 3, ranked key encoding, one-million-key pages, 8 KiB blocks,
disk format, fan-in, workers, queues, board computation and routing are unchanged.

## Metadata benchmark

Canonical profile: HestiaStore `senku-maintenance-hotpaths`. The metadata case
uses 128 shards × 64 runs = 8,192 immutable in-memory manifests, no eligible
merge jobs, and a primed coordinator. Fixtures deliberately contain metadata
only. The baseline calls the former periodic `scanAndScheduleOnce`; the candidate
calls `discoverAndScheduleOnce`. Setup is excluded from measurement.

Java 21.0.10, one JMH thread, two forks, two 500 ms warmups and four 500 ms
measurements per fork, `-Xms1g -Xmx1g`, `-XX:ActiveProcessorCount=4`, GC profiler,
and `nice -n 10` on the same Mac. MB below means decimal MB, not MiB.

| Summary buckets/run | Baseline allocation/scan | Candidate allocation/scan | Reduction | Baseline ms/scan | Candidate ms/scan |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 180.91 MB | 77.78 MB | 57.0% | 60.07 ± 13.14 | 32.75 ± 16.09 |
| 256 | 941.57 MB | 77.78 MB | 91.7% | 278.82 ± 47.92 | 34.30 ± 19.00 |

The candidate no longer scales repeated parsing/allocation with summary size.
Direct read-count tests separately prove no second manifest read on cached
discovery, and prove strict validation rereads it. Remaining allocation includes
structural name parsing, catalog/path construction and scheduling; it is not
zero. Completion wakes avoid this discovery pass entirely.

## Merge measurements: provisional, not a production speedup claim

The live solver continued throughout validation. The performance-review skill's
noise guard applies: CPU/core placement, shared memory/cache pressure, JIT and
changing production phases prevent reliable wall-time conclusions. The complete
canonical JMH observations are retained, including unfavorable directions:

| Sources | Duplicate input records | Baseline ms/merge | Candidate ms/merge |
| ---: | ---: | ---: | ---: |
| 4 | 0% | 30.61 ± 1.49 | 30.70 ± 10.35 |
| 64 | 0% | 70.53 ± 52.61 | 99.34 ± 34.04 |
| 4 | 50% | 26.88 ± 23.21 | 39.74 ± 23.58 |
| 64 | 50% | 88.98 ± 66.08 | 52.35 ± 4.36 |

Each merge consumes one million synthetic valid ranked input records, with
four or 64 interleaved sources, yielding one million or 500,000 unique keys.
All codec/page/compression settings match between versions. JMH's displayed
error estimates are shown above. Merge allocation remained approximately
unchanged: 22.82 MB for four-source distinct keys, 23.45 MB for 64-source distinct
keys, 15.46 MB for four-source duplicates and 13.52 MB for 64-source duplicates.

A separate diagnostic brackets the same complete synchronous merge with
`ThreadMXBean.getCurrentThreadCpuTime()`. One fresh JVM per version ran sequentially,
baseline then candidate, with eight warmups and 24 measurements for each case.
Output setup is outside timing. Current-thread CPU excludes descheduled time
and other threads' GC/JIT work, but still varies with cores, clock speed and
memory contention. It is supporting evidence, not an isolated throughput result.

| Sources | Duplicate input records | Baseline mean CPU ms | Candidate mean CPU ms | Observed reduction |
| ---: | ---: | ---: | ---: | ---: |
| 4 | 0% | 45.667 | 33.020 | 27.7% |
| 4 | 50% | 36.639 | 27.822 | 24.1% |
| 64 | 0% | 101.473 | 57.966 | 42.9% |
| 64 | 50% | 103.682 | 50.871 | 50.9% |

Repeat the canonical profile on a quiet host, then compare equivalent production
maintenance phases after controlled activation before claiming a live speedup.

## Exact output and compression

All four synthetic merge combinations produced identical raw compressed chunk
SHA-256 hashes, persisted manifest properties, exact counts and logical weighted
summary arrays between baseline and candidate. Only properties ordering and
timestamp comments were normalized; chunk bytes were not normalized.

| Duplicate input records | Unique output keys | Chunk bytes, either version | Total file bytes, either version |
| ---: | ---: | ---: | ---: |
| 0% | 1,000,000 | 1,286,144 | 1,291,004 |
| 50% | 500,000 | 655,360 | 660,201 |

The full round-12 corpus check also passed: 1,523,417 source states generate
25,004,471 legal moves and exactly **6,151,839 final states**. Every persisted
state was compared in sorted order with the independent corpus. Each version
ran one warmup and one measured round; all four outputs matched.

- Complete index: **5,035,456 bytes** in both versions.
- Density: **0.818529 bytes/state**, or **1.221704 states/byte**.
- Separate weighted-sample sidecar: **64,004 bytes** in both versions.

The one measured small round took 794.467 ms baseline and 1,252.790 ms candidate
through finalization and sidecar publication; ingestion alone varied from
338.094 to 624.782 ms even though this change does not alter ingestion code.
These contended, single-trial observations are not evidence of an end-to-end
speedup. Exact readback verification is outside that timing boundary. This
small frontier does not reproduce production's multi-billion-state backlog.

## Verification

- HestiaStore: all ten modules passed `mvn clean verify -DskipTests=false`.
  2,747 tests reported, zero failures/errors, one existing skip: engine 2,543
  unit tests and 87 integration tests; benchmark module 47 tests.
- Additional refined lifecycle run passed the completion-coalescing unit tests
  and 16 finalization/completion integration tests. It independently exercises
  both the first strict FINISHING scan and the final strict pre-READY scan.
- Peg-solitaire: isolated `mvn clean verify`, all 153 tests passed. All 644
  Hestia class files embedded in the candidate executable match the verified
  engine JAR byte-for-byte.
- Java-review checks covered randomized heap ordering, signed extremes,
  noncommutative duplicate order, arbitrary null-value callbacks, page boundaries,
  codec mismatch, malformed/truncated sources, reader cleanup and suppressed
  failures, cache publication/eviction, reservations and finalization races.
- New heap: 100% instruction and branch coverage. New metadata discovery:
  95.68% instruction coverage. Every changed production class exceeds 80%
  instruction coverage, with no new missed class.
- The normal Maven verify lifecycle reports coverage but does not bind
  `jacoco:check`. The report still contains the same 17 pre-existing missed
  classes as the previous checkpoint; this change does not satisfy that separate
  repository-wide zero-missed-class rule. No threshold was weakened.
- Formatter, strict MkDocs build, navigation validation, regenerated/visually
  checked lifecycle diagram and `git diff --check` passed.

## Artifacts and activation

The candidate is staged at
`target/candidates/senku-maintenance.OaD0fw/peg-solitaire-jar-with-dependencies.jar`,
with the engine JAR beside it. Candidate executable SHA-256:
`018202e19536fc70a31c11582fa0fa857648a713c1641005d8ee3587c9a7e94c`.

Builds, frozen source snapshots, raw JMH results, CPU/output probe source and
logs, test logs and corpus outputs are under
`/tmp/senku-maintenance-validation.M7UjWQ`. The baseline engine is a source
snapshot from before these maintenance edits, including the prior batching work,
not a clean historical Git commit. Benchmark fixture setup is the same except
the necessary old/new periodic-coordinator entry point noted above. Both
canonical runs use profile fingerprint
`71546fd9a8dc31bf9d3a838164c59cf2290fa505f6b513481012eff3fbf0558f`.

The live PID 20337, external-volume index, launcher and launcher-selected JAR
were not changed or restarted. The launcher JAR remains SHA-256
`1e4a08e933f1aebc0d62533316ba723d5825969fec57568f4c5447f4b643de78`.
The candidate is **not active**, including for automatic subsequent launcher
runs. The new engine was installed into the local Maven cache solely to build
the isolated application. No commit or push was performed.
