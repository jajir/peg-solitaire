# Senku batched ingestion and flush concurrency

Implemented on 2026-09-06. This change retains the current compression and
targets the CPU/admission bottlenecks observed in the running solver.

## Changes

- HestiaStore `SenkuLongSetWriting.putLongs(long[], offset, length)` is a real
  synchronous primitive batch API, not a loop over single-key puts. It hashes
  bounded 2,048-key windows outside mutation locks, groups keys by mutation
  stripe, and accepts at most 64 keys per lock hold. It uses the existing
  generation rotation, backpressure, finalization, and failure ownership.
- `SenkuLongSetMap` retains the configured routing hash in its existing hash
  array. Flush partitioning reuses it instead of evaluating the router twice
  more per key. The separate redundant stripe-size counters were removed.
- Oversized primitive-long shards are sorted in disjoint subranges on the
  existing shared flush pool, capped at four workers. Sorting is in-place,
  depth-bounded, and joins all child tasks before returning or failing.
- The shared page-preparation reservation allowance increases from 64 MiB to
  192 MiB. Four existing full one-million-key pages fit concurrently. Appends
  remain sequential and ordered. This allowance is not a total-heap limit;
  detached maps, ordering arrays, native compression state, and maintenance
  memory remain separate.
- Each peg-solitaire worker uses a reusable 4,096-child buffer. Exact pending
  deduplication is separate from the committed recent-key cache. Cache entries
  and submitted counts update only after a successful synchronous batch call.
  Partial buffers drain before a parent-processing task reports success.
  A failed buffer cannot be retried or reused. Generic destinations retain
  their previous single-key path.

Rank encoding, Zstd level 3, configured one-million-key pages, board generation,
symmetry canonicalization, and production worker/queue settings are unchanged.
No probabilistic filtering or state loss is introduced. A store batch is not
transactional: a failing call can have accepted a non-prefix subset. Racing
with finalization rejects remaining keys without rolling back accepted keys.

At the production cache setting, the new app arrays add approximately 80 KiB
per worker (about 640 KiB for eight workers). Hestia's routing scratch is bounded
to approximately 16.5 KiB per active batch call and reused across its windows.

## Exact-output and density validation

The checked corpus is the complete round-11 frontier (1,523,417 source states),
producing 25,004,471 legal moves and exactly 6,151,839 round-12 states. Every
persisted state was compared in sorted order against the independent corpus.
Three separate JVM trials per version each ran one warmup and one measured
round: all twelve outputs passed.

Both versions use the same READY-metadata sampling path. The benchmark mode
`batch-baseline` selects this path for the preserved pre-batching production
JAR; the older historical `baseline` mode includes a full output scan and is
not used for this comparison.

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Exact final states | 6,151,839 | 6,151,839 |
| Complete index bytes | 5,035,456 | 5,035,456 |
| Bytes/state | 0.818529 | 0.818529 |
| States/byte | 1.221704 | 1.221704 |
| Separate weighted-sample sidecar bytes | 64,004 | 64,004 |

Index size includes every regular index file, but excludes the separately
listed sidecar. This demonstrates unchanged density for this corpus, not a
promise of identical generation boundaries or file sizes for all future runs.
Batch-local duplicate filtering and worker scheduling can change submitted
counts while preserving the exact final state set.

An additional deterministic flush probe used four million synthetic valid
49-bit/28-peg ranked states, separately with uniform routing and 75% of keys in
one shard. Baseline and candidate compressed chunk files and shard-index files
were **byte-for-byte identical** in both cases. Manifest properties also
matched; only their generated timestamp comments differed. Warm/measured
repeats matched under the same timestamp normalization.

| Synthetic flush | Baseline bytes | Candidate bytes | Bytes/key |
| --- | ---: | ---: | ---: |
| 75% hot shard | 2,564,247 | 2,564,247 | 0.641061750 |
| Uniform routing | 9,666,711 | 9,666,711 | 2.416677750 |

These include the committed chunk, shard index, and manifest. They are codec
determinism fixtures, not estimates of production density. The baseline fixture
uses its original primitive-routing constructor; the candidate uses cached
routing hashes. No generic fallback or different codec is compared.

## Provisional timings, not a production speedup claim

The live solver continued running throughout validation. CPU contention and
maintenance timing make these results unsuitable for a clean speedup claim.
The performance-review skill's noise guard is therefore applied: retain the
observations, but defer a reliable throughput conclusion until a quiet-host
comparison or a controlled production trial.

Identical settings: Java 21.0.10, `-Xms2g -Xmx2g`,
`-XX:ActiveProcessorCount=4`, eight app workers, queue capacity 32, 4,096 parents
per task, recent cache 65,536, and `nice -n 10`. Temporary indexes used the local
filesystem, not the live external-volume index. Version order was
baseline/candidate/candidate/baseline/baseline/candidate.

| Measured phase | Baseline median (range), ms | Candidate median (range), ms |
| --- | ---: | ---: |
| Ingestion | 991.621 (749.445–1,017.764) | 431.127 (421.293–534.956) |
| Finalization | 1,102.896 (924.830–1,744.261) | 725.070 (525.174–2,297.064) |
| Round through metadata/sidecar | 2,109.781 (1,957.295–2,512.933) | 1,168.286 (1,071.157–2,740.311) |

Exact persisted-output verification occurs after the round timing boundary.
The candidate's slowest round exceeded every baseline round; reporting only
the median would conceal that variability. These small-round results also do
not reproduce the current multi-billion-state production maintenance load.

Single warmed synthetic-flush observations were 516.981 ms baseline versus
270.023 ms candidate for the hot shard, and 294.183 ms versus 336.914 ms for
uniform routing. The mixed directions reinforce the need for a quiet-host,
repeated comparison: neither is evidence of a stable speedup or regression.

HestiaStore now contains the canonical `senku-batched-flush` JMH profile. It
covers a four-million-key ranked flush with uniform routing and a 75% hot shard,
plus single-key versus 4,096-key batch admission. Ingestion scores are normalized
per key. Run the full profile on a quiet host before making performance claims.

## Verification

- HestiaStore: full ten-module `mvn clean verify -DskipTests=false` passed.
  Engine: 2,521 unit tests and 83 integration tests (one existing skip).
  Benchmark module: 43 tests passed, including profile-contract checks.
- Peg-solitaire: `mvn clean verify` passed, 153 tests.
- New Hestia `SenkuLongBatch` and `SenkuLongSortTask`: 100% instruction coverage;
  the new primitive key/hash consumer is an interface without executable code.
- Concurrency cases cover rotation mid-batch, batch sizes larger than the
  routing window, interrupted admission, concurrent finish, first-failure
  preservation, worker/control shutdown, root-lock release, and overlapping
  preparation of full one-million-key pages.
- Buffer tests cover exact collisions, pending duplicates, successful-only
  accounting/cache updates, full and partial boundaries, disabled caching,
  destination failure, and blocked-batch cancellation.
- Strict MkDocs build, documentation navigation validation, diagram rendering,
  formatting, and `git diff --check` passed.
- A separately invoked `jacoco:check` still fails its repository-wide
  zero-missed-class rule on 17 unchanged classes outside this work. The exact
  same 17 classes appear in the pre-change report; no new class is missed.
  The normal `verify` configuration produces a coverage report but does not
  bind this check goal. No coverage threshold was weakened.

## Artifacts and activation

Builds and validation were isolated under
`/tmp/senku-batch-validation.Ywccnq`. Raw build, coverage, and `round12-*.log`
files remain there. The baseline is the preserved running production JAR,
SHA-256 `16c185808073bafb9bfc6ef4e34b0500136d59c18a1bb480e0136a62662e86fd`.

The verified candidate executable and engine JAR are staged separately in
`target/candidates/senku-batched.7nHeuu/`. Candidate executable SHA-256:
`c98c7933bd387b8f659cd7614419f9ffaa78fa2552913378fc26ccb617fb79b0`.

The running process, live index, launcher script, and the JAR currently selected
by the launcher were not replaced or reconfigured. The candidate is **not
active**, including for the launcher's automatic subsequent runs. Activating it
requires a controlled deployment/restart. No commit or push was performed.
