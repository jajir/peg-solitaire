# Senku Pipeline Results

Measured on 2026-09-05 on the M4 / 10 logical CPUs / 16 GiB machine.
Existing work was committed before implementation:

- HestiaStore: `71de83d76` (branch `senku`).
- peg-solitaire: `8633107` (branch `main`).

No solver was running during measurements. The production data root was not
modified and no solver was started. All benchmark indexes are under
`/tmp/senku-pipeline.IvY5bd` on the internal filesystem, not the external HFS+
volume. Nothing was pushed.

## Implemented Scope

1. Maintenance compares and writes encoded ranks within matching immutable
   codec domains. Generic reducers and public streams still receive logical
   keys. Rank bounds, varint framing and ordering checks remain enabled.
2. Generic ingestion uses one probe except when resizing. The application
   explicitly selects `SenkuMergeFunctions.longSet()`,
   `createLongSet(LongToIntFunction)` and `putLong(long)`, avoiding boxed board
   keys and value arrays. Admission, rotation and failure handling still belong
   to the existing ingestor. No array/batch API was introduced.
3. Built-in long flush pages are prepared on the same bounded pool used for
   sorting. A shared 64 MiB Java-page working-byte budget and one ordered append
   owner preserve memory and transaction ordering. This is not a total heap cap.
4. Partial finalization merges and singleton promotions wait for pending L0
   batches and committed flushes. Full-fan-in compactions remain eligible.
5. Ready counts come from terminal manifests. Output runs collect at most 256
   weighted representatives each, combined into at most 4,096 for routing.
   Sidecar v2 distinguishes approximate weighted distributions from legacy
   exact-ordinal samples. Missing summaries fall back to scanning.

`count --verify-ready` retains the expensive full output scan for diagnosis.
Metadata is not a substitute for checking all stored pages and keys. The
`stats` command is unchanged.

## Exact Round-12 Comparison

Both jars use the same 1,523,417 source states, fixed-weight/parity rank codec,
Zstd-3, source-sample routing, eight workers, queue 32, 4,096-parent tasks and
65,536-entry recent-state cache. Both generate exactly 25,004,471 legal moves
and persist exactly **6,151,839 unique states**.

Three isolated JVM trials per jar, each with one warmup and one measured run;
order alternates between trials. Java 25.0.2, `-Xms2g -Xmx2g`, equal JVM flags.
The source frontier and its sample are loaded before timing. Timings include
routing setup, ingestion, finish, output distribution and sidecar publication;
they exclude reading the persisted source index, root discovery and round
directory promotion. Thus this is not a timed invocation of `run-senku.sh`.

Every output was reopened and compared key-for-key with the complete expected
corpus after the production timing boundary. The candidate is not considered
correct merely because its manifest reports the expected count.

| Mean of three measured trials | Checkpoint | Candidate |
| --- | ---: | ---: |
| Ingestion | 1,060.112 ms | 573.609 ms |
| Final flush and maintenance | 1,988.961 ms | 835.501 ms |
| Output distribution, close and sidecar | 1,021.716 ms | 8.777 ms |
| Total after source loading | **4,076.430 ms** | **1,423.408 ms** |
| Independent reopen/full verification | 895.495 ms | 914.720 ms |
| Total including independent verification | 4,971.925 ms | 2,338.128 ms |

The measured production portion is **2.864× faster (65.1% less time)**.
Individual totals: checkpoint 4,011 / 4,273 / 3,946 ms; candidate
1,503 / 1,409 / 1,358 ms. Cache submissions vary slightly with worker scheduling
(approximately 18.43 million); all generated moves and final keys are identical.
Three trials are not a prediction for a billion-state round or an external disk.

### Storage Tradeoff

All **128 chunk files are byte-for-byte identical**, totaling 4,521,984 bytes in
each version. Key-page compression and density have not regressed.

| Complete output accounting | Checkpoint | Candidate |
| --- | ---: | ---: |
| Index including manifests | 4,544,140 B | 5,035,456 B |
| Sidecar | 24,068 B | 64,004 B |
| Combined | 4,568,208 B | 5,099,460 B |

The new bounded metadata costs **531,252 additional bytes** here, or 11.6% for
this relatively small frontier. The count/routing speedup therefore has a real
space cost. With a fixed shard count and sample limits, this metadata does not
grow in proportion to the number of states; its fraction shrinks on large
frontiers. No new large-frontier density measurement was performed.

## JMH Hot Paths

The canonical `senku-pipeline` profile covers ranked maintenance, a one-million
entry/128-shard in-memory flush, and eight-thread filesystem ingestion with a
one-million-entry rotation threshold. Both artifacts ran on Java 25.0.2 with
matching benchmark settings and GC profiling. Values below are means; ± values
are JMH's reported confidence intervals, not standard deviations.

| Workload | Checkpoint | Candidate | Interpretation |
| --- | ---: | ---: | --- |
| Rank merge, 100k fixed-weight keys | 12.052 ± 0.169 ms | 1.636 ± 0.053 ms | **7.37× faster** |
| Numeric-delta merge, same keys | 1.712 ± 0.029 ms | 1.822 ± 0.025 ms | 6.4% slower; summary overhead |
| In-memory flush | 66.429 ± 1.830 ms | 60.784 ± 5.520 ms | 8.5% lower mean, overlapping intervals |
| Generic filesystem ingestion | 10.83 ± 6.92 M puts/s | 18.31 ± 2.16 M puts/s | Baseline is phase-sensitive/noisy |
| Explicit primitive-set ingestion | unavailable | 28.37 ± 1.81 M puts/s | 1.55× candidate generic mean |

Rank-merge allocation rises from 787,226 to 855,138 B/op (+8.6%); numeric-delta
allocation rises from 1,169,231 to 1,240,658 B/op (+6.1%). These include bounded
summary collection and manifest serialization. Flush allocation remains about
88.42 MB/op. The short sustained-ingestion benchmark's allocation/throughput
averages span rotations and maintenance phases, so do not interpret them as
the allocation of an isolated put.

The numeric-delta regression is retained as an explicit tradeoff of collecting
ready summaries. The application uses ranked pages, where the CPU saving is
large and the independently verified round comparison improves substantially.
There is no claim of uniform improvement for every Senku workload.

## Verification and Reproduction

- HestiaStore `mvn clean verify -DskipTests=false`: all modules passed;
  2,495 engine unit tests, 79 integration tests (one existing skip), and
  109 tests across other modules, including 39 benchmark tests.
- New primitive map: 96.6% instruction coverage; flush pipeline: 94.5%; page
  task and shared executor: 100%. The engine coverage gate passed.
- peg-solitaire `mvn clean verify` on Java 21: 137 tests passed; launcher jar
  rebuilt and Java-21 command-help smoke test passed.
- Hestia documentation navigation and strict MkDocs build passed; thread
  diagram source and rendered PNG are synchronized.
- All six measured/warmup output pairs passed complete key verification.

Raw logs, JSON, retained production jars and benchmark indexes:
`/tmp/senku-pipeline.IvY5bd`. These are local diagnostic artifacts and may be
removed by temporary-directory cleanup.

Artifact SHA-256 values:

```text
baseline app:   ff6f49656fc8d2e05fb5a604cb875d8382a5b6b4969da078701793dcff94ef2d
candidate app:  16c185808073bafb9bfc6ef4e34b0500136d59c18a1bb480e0136a62662e86fd
baseline JMH:   31a967e2bcca6ff4724a24439f8bf5abe5a0fc36f8efe1ede5ef8b1d035283ca
candidate JMH:  62327851c8270ed157265c2cc0816bc0d3c3d210c83ba8a1c271a160b8d5ec26
```

From HestiaStore, with the measured Java on `PATH`:

```sh
python3 benchmarks/scripts/run_jmh_profile.py --repo-root . \
  --profile senku-pipeline --skip-build \
  --jar /tmp/senku-pipeline.IvY5bd/candidate-benchmarks.jar \
  --output-dir /tmp/senku-pipeline-recheck
```

The checkpoint benchmark has no `api` parameter: use matching individual
profile commands without that parameter for its generic baseline. Do not
collapse parameterized results by benchmark name or use throughput direction
to interpret latency.

From peg-solitaire, run each jar in a separate JVM, alternating their order:

```sh
java --enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g \
  -cp target/test-classes:/tmp/senku-pipeline.IvY5bd/baseline-app.jar \
  cz.coroptis.pegsolitaire.SenkuPipelineBenchmark \
  /tmp/senku-range-sharding.LMAMcd /tmp/senku-pipeline-recheck-round baseline
java --enable-native-access=ALL-UNNAMED -Xms2g -Xmx2g \
  -cp target/test-classes:/tmp/senku-pipeline.IvY5bd/candidate-app.jar \
  cz.coroptis.pegsolitaire.SenkuPipelineBenchmark \
  /tmp/senku-range-sharding.LMAMcd /tmp/senku-pipeline-recheck-round candidate
```

Do not put `target/classes` on that classpath: each trial must load its actual
production classes from exactly one preserved jar. The harness uses reflection
only once at the candidate metadata boundary, never in a per-state hot loop.
