# Senku live performance — 6 September 2026

The current limit is CPU work in ingestion and flush preparation, with frequent
backpressure that stops board workers while the flush pipeline catches up.
The captured intervals do not show disk bandwidth, garbage collection, swapping,
or a deadlock as the primary bottleneck.

## Running job and scope

- PID **76733**, launched by `bin/mac/run-senku.sh`; OpenJDK **21.0.10**.
- M4, **4 performance + 6 efficiency cores**, **16 GiB RAM**.
- `-Xmx10g`, **8 board workers**, **32 queued batches**, 4,096 parents per batch.
- Storage configuration: 10 million distinct entries per ingestion generation,
  128 shards, 8 maintenance threads, merge fan-in 64, Zstd level 3, 8 KiB I/O buffers.
- Destination: `/Volumes/ponrava/senku-hestia/20.in-progress`; source: round 19.
- Data is on USB volume `ponrava`, Journaled HFS+, with **83.4% free space**.
- The loaded JAR is the candidate from `senku-pipeline-results.md`, SHA-256
  `16c185808073bafb9bfc6ef4e34b0500136d59c18a1bb480e0136a62662e86fd`.
  Ingestor, flush writer, page pipeline, and merge writer class files also match
  the adjacent HestiaStore build.

The live run continued throughout this inspection. No application code,
launcher settings, or production data were changed by the investigation.
Both diagnostic recordings stopped automatically.

## Measurements

Two JFR recordings were taken on 6 September, Europe/Prague time:

1. **06:41:05–06:42:05:** 60 seconds with the installed `profile.jfc` settings,
   including 10 ms Java execution sampling and 10 ms park/file event thresholds.
   `iostat`, `top`, JVM thread dumps, and VM statistics supplied independent evidence.
2. **06:46:29–06:46:59:** 30 seconds with all Java file read/write durations recorded
   and a 1 ms park/monitor threshold; allocation sampling disabled.

| Measurement | 60-second profile | 30-second detailed profile |
| --- | ---: | ---: |
| JVM CPU, percent of one core | **651% average** | **707% average** |
| Whole-machine CPU utilization | 68.5% | 74.1% |
| Worker time waiting at ingestion admission | **151.65 thread-seconds / 31.6%** | **71.27 thread-seconds / 29.7%** |
| Worker time waiting for flush pages and sorting | **35.89 thread-seconds / 7.5%** | **18.42 thread-seconds / 7.7%** |
| Worker time in recorded mutation-lock parks | 1.59 thread-seconds / 0.3% | 4.00 thread-seconds / 1.7% |
| GC stop-the-world time | **0.377 s / 0.63%** | **0.188 s / 0.63%** |
| Longest GC pause | 9.92 ms | 9.03 ms |

Worker percentages divide summed, window-clipped wait durations by eight workers
times recording duration. They are recorded waits above each threshold, not
complete thread-state accounting. Simultaneous waits overlap in wall time;
these percentages are not a promised recoverable speedup. Shorter lock waits
remain unmeasured. There was no Java monitor-enter event in the first capture;
the second recorded one, in a JFR service thread.

### Why the workers stop

`SenkuIngestor` allows one active generation and one detached generation being
flushed. A board worker that claims rotation also runs the flush. When the new
active generation reaches its limit before that flush finishes, other workers
park in `awaitAdmission()` until rotation can proceed. Maintenance queue pressure
can also close this same gate; the park stack alone cannot distinguish those flags.

Thread dumps captured normal insertion, a worker doing flush ordering, and later
seven workers waiting at the admission condition while another owned the flush.
JFR recorded repeated joins in `SenkuFlushWriter.sortShards()` and
`SenkuFlushPagePipeline.appendFirst()`. This is productive backpressure in the
storage pipeline, rather than evidence of a deadlock.

The input producer waits for worker completions. With 40 batches in flight, the
queue is already providing work; increasing `--queue-capacity` does not address
the downstream gate.

### Where CPU work goes

Across the two captures, the distribution of Java/native execution samples was:

| Thread group | Share of samples |
| --- | ---: |
| Board workers, including the worker owning a flush | 53–58% |
| Parallel flush preparation | 21–22% |
| Maintenance/merge workers | 15–22% |
| Input reader | 4% |
| Coordinator | 1% |

Specific hotspots repeated in both captures:

- **`SenkuIngestor.putKey`, attributed to line 180 (`mutationLock.unlock()`)**:
  21–24% of all top-frame samples. The ingestion synchronization/update path is
  expensive. Optimized JVM line attribution does not prove that every such sample
  is time spent in the unlock instruction itself.
- **`LongFixedWeightRank.count`**: about 12% of all top-frame samples, chiefly
  called by rank encoding during flush page preparation. Rank encoding accounts
  for much more flush CPU than Zstd compression in this profile.
- **`CompiledBoardKernel.transformParent`** and **`RangeShardRouter.shard`**:
  visible costs in board processing and per-key routing.
- **Priority-queue comparisons in `SenkuLongMergeWriter`**: the major maintenance
  CPU cost; the samples show encoded-key merge work rather than predominantly
  blocked file reads.

Execution samples indicate hotspots; their percentages are not exact CPU-time
measurements. The independent CPU-load measurements show substantial utilization
with remaining idle capacity and bursts close to full utilization.

### Disk and memory

External `disk4` traffic over the 60-second system sample averaged **15.27 MB/s**,
median **6.66 MB/s**, range **2.54–73.06 MB/s**. These are combined physical reads
and writes, excluding `iostat`'s initial since-boot average. Traffic comes in
maintenance bursts, separated by quieter ingestion intervals.

The detailed recording captured **138,597 file reads** and **37,503 writes**:

- Summed Java-visible file-operation durations across all threads: **1.003 s**
  in 30 seconds.
- Main input reader: **0.0155 s** total file-read time.
- Maintenance reads: **0.793 s** total; 99th percentile **0.51 ms**, max **15.23 ms**.
- Flush-owner writes: **0.040 s** total.

This supports disk I/O being secondary in these intervals. Java file events can
be satisfied by the OS cache and do not measure physical device service time,
all metadata operations, or every kernel wait. The measurements are not a disk
speed benchmark and do not exclude disk limits in a later finalization phase.

The heap snapshot showed **1.68 GiB committed**, **0.96 GiB used**. `-Xmx10g` is a
ceiling, not the current heap allocation. The system had about **3.46 GiB** of
existing swap usage, but the VM sample recorded **zero new swap-ins or swap-outs**.
There were no full GCs; pause time stayed near 0.6%. Memory pressure and GC were
not the immediate limiter in these captures.

## Overall progress and earlier stalls

The log contains completed rounds:

| Round | Input parents | Unique output states | Total elapsed |
| --- | ---: | ---: | ---: |
| 17 → 18 | 2,171,225,023 | 5,499,266,920 | 53m 28.7s |
| 18 → 19 | 5,499,266,920 | 12,736,742,032 | 2h 25m 32.5s |

The current **19 → 20** round therefore has **12,736,742,032 input parents**.
At **06:51:06**, it had processed **12,209,471,488**, or **95.86% of the input**.
The recent ten-minute rate measured at 06:47 was about **720,000 parents/s**;
the current round's logged average to that point was about **651,000 parents/s**.
Output finalization/compaction follows input processing, so input percentage is
not total elapsed-work percentage.

The larger frontiers explain much of the longer runtime. The log also shows
almost no progress around **05:33–05:44**, with multiple whole minutes unchanged,
followed by recovery. Those earlier stalls were not profiled. Their cause cannot
be established retrospectively from the progress log alone; the measured
admission/flush waits are a concrete candidate to instrument.

The progress message's denominator is **submitted states**, not the whole source
frontier. Its usual gap of 159,744–163,840 states reflects the bounded 39–40
outstanding batches. It does not mean the round is perpetually almost finished.

## Recommended next work

1. **Reduce flush preparation time**, starting with fixed-weight/parity rank
   encoding and sorting. This directly targets the pipeline that is holding
   board workers at admission. Preserve codec validation and exact output.
2. **Isolate ingestion synchronization cost** in a representative sustained
   workload: `putKey` is the largest sampled hotspot. Evaluate shorter or
   amortized critical sections and cache-line contention before adding workers.
3. **Add phase metrics**: admission pause reason/duration, rotation and flush
   timings, sort/page preparation timings, maintenance backlog, and generated vs
   submitted moves. Log processed parents against the actual source count.
   These would explain future multi-minute stalls without guessing.
4. **Then benchmark worker/pool balance** with the real ranked, flushing workload.
   Eight board workers plus maintenance and preparation pools share ten unequal
   cores. Neither a larger input queue nor more threads is established as a fix.

A faster disk or a larger heap is not supported as the first intervention by
these measurements. No performance change or speedup is claimed without a
controlled comparison.

## Diagnostic artifacts

Raw recordings, system samples, thread dumps, compact event exports, and the
analysis scripts are in `/tmp/senku-live-profile.Yuwazp/`:

- `senku.jfr` — 60-second profile.
- `io-detail.jfr` and `io-detail.jfc` — detailed 30-second capture and settings.
- `summary.json`, `summary.txt`, `summarize.py`, `DumpJfr.java` — derived evidence
  and reproduction scripts.
- `iostat.txt`, `top.txt`, `vmstat.txt`, `threads-*.txt`, `heap.txt` — system/JVM
  observations.

These are temporary local files; this report is retained in the project.
