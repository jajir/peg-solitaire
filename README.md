# peg-solitaire

## Senku storage compression

The round enumerator creates HestiaStore Senku indexes with fixed-population,
parity-class ranking followed by delta-varints and `Compression.zstd(3)`.
`BoardStateEncoding` derives four conserved binary parities from the actual
board geometry. Each page stores the numeric rank among boards with the same
peg count and parities: an absolute first rank, then variable-length gaps.
HestiaStore reconstructs the original keys when reading. All 49 board bits,
canonicalization, values, and the set of generated states remain unchanged.

The generic library configuration is
`KeyPageCodecs.longFixedWeightDeltaVarint(bitCount, setBitCount, parityMasks, paritySyndrome)`.
Its complete immutable domain is persisted in `format.properties` (codec ID 4),
so reopening requires no application callback. This works for English,
European, and Senku boards. Population is inferred from source keys, not the
round directory number. Zero/one-peg destinations use ordinary deltas to allow
empty terminal indexes even when no such board exists in the initial parity
class. A manually supplied source outside the initial class uses population
ranking without parity restrictions. Mixed-population samples are rejected.

The general-purpose `HestiaRoundStore.create` overloads still default to
`KeyPageCodecs.longDeltaVarint()` (ID 3). Previous delta/Zstd and prefix/Zstd
indexes with format metadata remain readable; completed rounds are never
rewritten by advancing the search.

The HestiaStore dependency must include the new key-page codec API. When using
the sibling HestiaStore checkout, build and install its engine locally before
building this application:

```sh
mvn -f ../HestiaStore/pom.xml -pl engine -am install
mvn clean verify
```

This is a breaking Senku disk-format change. Newly created indexes persist
`format.properties`; old Snappy indexes cannot be opened by the new reader.
Use a fresh output directory for a new run and retain any old data you need.
There is no automatic conversion or deletion of existing indexes.

## Board computation and batching

`CompiledBoardKernel` precomputes jump transformations and byte-table symmetry
lookups. It detects legal moves in a temporary row-major bitboard, then emits
exactly the same Hilbert-encoded canonical keys as the simple reference engine.
The reference engine remains available as an independent test oracle.

Workers process batches of 4,096 source states; `--queue-capacity` now limits
queued **batches**, not individual states. Each worker has a 65,536-slot recent
output cache (full 64-bit keys plus occupancy bits, about 520 KiB). It skips a
write only after an exact match to a successfully submitted key in this round.
Hash collisions cause misses/evictions, never lost states. The persistent index
still performs complete deduplication, and reported generated-move counts still
include duplicates. Cache state is discarded between rounds. Defaults use
about 4.06 MiB for eight worker caches plus bounded input batches.

See [the combined optimization benchmark](doc/round-optimization-results.md).

## Forecast range sharding

The round enumerator now keeps numerically adjacent states in the same shard,
which gives the delta-varint/Zstd codec more local patterns to compress. It
forecasts the next round from at most 4,096 sampled source states, canonicalizes
their successors, and chooses up to 128 approximately equal-sized numeric
ranges. The boundaries stay fixed for that entire writing session, including
all flushes and merges. They are estimates, not a guarantee of equal shard size.

HestiaStore uses the configured hash for ingestion maps as well as persistent
shards. The router therefore mixes the **full 49-bit state** for hash-table
distribution and places the range ID in only the bottom seven bits. Returning
just a range ID would cause severe hash collisions.

Each completed round saves a checksummed `N.state-sample` file beside its `N/`
index directory, atomically before publishing the round. Exact counts come from
validated terminal run manifests. Run writers collect at most 256 natural-key
representatives while emitting deduplicated output; only selected ranked keys
need decoding. Ready metadata combines the terminal distributions into at most
4,096 representatives, avoiding a separate full-output counting/sampling scan.

Version 2 sidecars contain approximate representative keys with positive
weights summing to the exact state count, occupying at most 65,572 bytes.
They are **not** exact global-ordinal samples and have no formal quantile-error
guarantee. Forecast successors inherit and combine their parents' weights.
Version 1 exact-stride sidecars remain readable. If a sidecar is missing, the
ready summary is used; older indexes without summaries fall back to a full
read-only scan. Malformed summaries are rejected. Sampling affects placement
only, never which states are generated or retained; source representatives also
select the next round's population and parity domain.

For an explicit expensive count/order and page-integrity diagnostic, add
`--verify-ready` to the `count` command. This restores a complete stream scan
after each finalized output. Metadata counts alone do not audit duplicate keys
across different shards or validate every stored data page.

This routing change can read the new delta/Zstd indexes written with the
previous prefix-hash router. It does **not** make old Snappy indexes readable.
Completed indexes are not recompressed in place. Rebuild the application to use
the new router for subsequent rounds; `HestiaRoundStore.create(Path)` remains a
prefix-hash fallback for callers that do not supply a source forecast.

See [the measured results and reproducible benchmark](doc/range-sharding-results.md).

See [the pipeline implementation and before/after results](doc/senku-pipeline-results.md)
for primitive ingestion, encoded-rank maintenance, bounded page preparation,
metadata-based counting, and their measured CPU/storage tradeoffs.
