# Compressed page assembly — implementation and comparison

The change is implemented and correctness checks pass. Fragmented-page
materialization allocates substantially less memory. **Timing remains provisional:
the existing Senku solver ran throughout these benchmarks, using roughly seven to
eight CPU cores. This report does not clear the no-overall-regression check or claim
a whole-solver speedup.** Permission to pause the existing solver was requested;
no pause was performed.

## Implementation

In HestiaStore, `ConcatenatedByteSequence.computeByteArray()` now allocates
`new byte[totalLength]` and calls `copyTo(0, combined, 0, totalLength)`. Nested
concatenations and slices copy into that final destination without materializing
child arrays. Root caching and defensive-copy behavior are preserved.

The relevant Senku path is reading a compressed page across several data blocks:
`DataBlockByteReaderImpl` collects slices, `ChunkData` trims padding, and
`ChunkFilterZstdDecompress` requests the contiguous array before decompression.
Single-slice reads and the normal page write path do not need this optimization.
Compression settings, checksums, codec configuration, and stored bytes are unchanged.

## Comparison

Means below come from identical benchmark harnesses and dependencies. Allocation
is normalized bytes per operation, including sequence wrappers and other measured
work. Confidence intervals and exact allocation values are retained in
[comparison.json](benchmark-data/compressed-page-copy-2026-09-15/busy/comparison.json)
and the adjacent raw JMH files. Negative allocation reductions near zero are noise.

| Case | Before | After | Time/throughput change | Allocation reduction |
| --- | ---: | ---: | ---: | ---: |
| 64 KiB, 1 fresh slice(s) | 1.820 µs/op | 1.937 µs/op | 6.4% more time | -0.0% |
| 64 KiB, 4 fresh slice(s) | 8.798 µs/op | 2.655 µs/op | 69.8% less time | 66.6% |
| 64 KiB, 16 fresh slice(s) | 15.180 µs/op | 2.525 µs/op | 83.4% less time | 79.8% |
| Two flat arrays, 128 bytes | 0.022 µs/op | 0.024 µs/op | 9.7% more time | -0.0% |
| Two flat arrays, 65536 bytes | 2.125 µs/op | 2.153 µs/op | 1.3% more time | -0.0% |
| 64 KiB, cached child trees | 2.214 µs/op | 2.473 µs/op | 11.7% more time | -0.0% |
| Full Zstd read, 4 KiB decoded | 1.987 µs/op | 1.944 µs/op | 2.2% less time | 0.0% |
| Full Zstd read, 64 KiB decoded | 18.091 µs/op | 14.414 µs/op | 20.3% less time | 34.5% |
| Merge, 4 sources, 1M input records | 18.723 ms/op | 17.488 ms/op | 6.6% less time | 17.9% |
| Merge, 64 sources, 1M input records | 36.070 ms/op | 40.017 ms/op | 10.9% more time | -0.0% |
| Steady 4 KiB chunk writes | 911625.118 ops/s | 891850.741 ops/s | 2.2% lower throughput | -0.0% |

For fresh four-slice pages, time changed from 8.80 to 2.65 µs (3.31× as fast), and
allocation from 197,000 to 65,832 B/op. For sixteen slices, time changed from 15.18
to 2.52 µs (6.01× as fast), and allocation from 329,272 to 66,648 B/op. These large
allocation reductions match the removal of intermediate arrays. Exact timing
ratios should not be extrapolated to an entire search round.

The multiblock read fixture stores 32,791 compressed bytes across five 8 KiB
blocks and decodes to 64 KiB. Its complete in-memory read, block/CRC work, and
Zstd decompression changed from 18.09 to 14.41 µs, with allocation falling from
214,904 to 140,856 B/op (34.5%). The 4 KiB decoded fixture stores 2,069 compressed
bytes in one block and acts as an unaffected read control. This chunk-store
fixture includes chunk CRC validation beyond the usual Senku filter chain.

## Regression checks and limits

- The 64-source merge initially averaged 10.9% slower. A longer reverse-order
  repeat with five forks and one-second iterations gave **36.791 ± 3.074 ms before
  versus 38.436 ± 1.939 ms after**, a 4.5% difference with overlapping JMH
  confidence intervals. Its allocation stayed effectively unchanged.
- An untimed, instrumented diagnostic counted **zero calls to the changed method
  in both versions of the 64-source merge**. It is an unaffected control for this
  fixture. The four-source merge counted 76 materializations before and 4 after;
  concatenation destination bytes fell from 2,762,980 to 631,060. These counts
  exclude leaf-slice allocations. Every probe emitted exactly 500,000 records.
- Tiny flat-array and cached-child controls averaged 2.10 ns and 0.259 µs slower,
  respectively; their intervals overlap. These do not establish regressions or
  demonstrate equivalence under the current load. Cached children are a real
  structural tradeoff: the new path traverses underlying pieces even when child
  flattened arrays already exist. No current Senku/engine caller using that
  reuse pattern was found during review.
- The unchanged single-slice control was also 6.4% slower, and steady writes
  averaged 2.2% lower throughput, with overlapping intervals. Concurrent load
  prevents using small differences as an overall performance sign-off.

**Next validation step:** rerun the canonical profile on a quiet host and resolve
any reproducible slowdown before treating overall performance as cleared. The
repository benchmark-regression-check skill requires like-for-like environments
and warns against interpreting a single noisy result. No code was changed in
response to these inconclusive control timings.

## Correctness and compatibility

- `mvn clean verify -DskipTests=false` passed across all HestiaStore modules:
  **2,757 tests passed**, plus one pre-existing `@Disabled` stress-test case.
  This includes 2,547 engine unit tests, 86 engine integration tests, and 54
  benchmark-module tests. The verification pipeline and coverage gate passed.
- The separately rebuilt peg-solitaire application passed `mvn clean verify`:
  **153 tests passed**.
- New tests cover nested uneven slices, nonzero offsets, zero padding, empty
  inputs, cached-array identity, defensive-copy isolation, and forbidden child
  materialization. The Zstd integration test verifies actual multiblock compressed
  input, exact compressed bytes, headers, CRC, decoded output, and EOF.
- Baseline and candidate wrote byte-identical 8,192-byte and 40,960-byte fixture
  files. Each build read the other build's files with exact decoded bytes and
  clean EOF. See [compatibility.txt](benchmark-data/compressed-page-copy-2026-09-15/compatibility.txt).
- Independent Java review found no blocking correctness issue.

## Reproduction and evidence

Baseline HestiaStore commit: `f063b0525703756b97f7a36a5369f875b1c59885`.
Implementation commit: `285b1faa578ee8ed09fbe87697983b81901ca6cd`.
The candidate is the baseline checkout plus the recorded method change and tests.
Both builds use the same new canonical `concatenated-page-read` profile.

Hardware: Apple M4, 16 GiB RAM, macOS. Benchmark runtime: OpenJDK 21.0.10;
Maven builds used OpenJDK 25.0.2 with Java 17 release targets. The primary profile
uses three JVM forks, three 500 ms warmup iterations, five 500 ms measurements,
one benchmark thread, `-Xms1g -Xmx1g -XX:ActiveProcessorCount=4`, and `-prof gc`.
Baseline/candidate order alternates across profile entries. No builds or other
benchmarks ran concurrently with the timing comparisons; the existing solver
continued running. The initial comparison ran on 15 September 2026, approximately
07:22–07:27 Europe/Prague, followed by the longer 64-way repeat.

JMH-generated class files differed between independent builds, so the paired
runner JARs use one identical compiled harness. The verified candidate production
class replaces only `org/hestiastore/index/bytes/ConcatenatedByteSequence.class`.
Every other JAR entry is byte-identical. See
[paired-jars.json](benchmark-data/compressed-page-copy-2026-09-15/paired-jars.json).
The application contains the same candidate class that was benchmarked.

From the HestiaStore checkout, build and run the canonical profile for each
version with identical benchmark sources:

```sh
mvn -pl benchmarks -am package
python3 benchmarks/scripts/run_jmh_profile.py --repo-root . \
  --profile concatenated-page-read --skip-build \
  --jar /path/to/paired-version.jar --output-dir /tmp/hestia-bench/page-read-version
```

Saved [evidence metadata](benchmark-data/compressed-page-copy-2026-09-15/busy/metadata.json)
contains all executed commands, JVM settings, profile fingerprint, ordering, and
solver observations. The parameter-aware `summarize.py` compares each method and
parameter set separately and respects average-time versus throughput direction.
The longer repeat, call-count diagnostic, compatibility probe, verification counts,
and profile are retained beside the primary raw results.

## Application artifact

The updated library is installed in the local Maven repository. A separately
verified application is available at
`target/peg-solitaire-page-copy-candidate.jar` (SHA-256
`af70a39a258ecf0462f54b656288ccdf619b4a42d0bfebebb8377316ee0648fb`).
The currently running solver and its original JAR were left unchanged; the new
candidate has not been activated in that run. Its original JAR hash was checked
again after the work. See [application.json](benchmark-data/compressed-page-copy-2026-09-15/application.json).
