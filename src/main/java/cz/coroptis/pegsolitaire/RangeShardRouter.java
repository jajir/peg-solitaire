package cz.coroptis.pegsolitaire;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Immutable numeric ranges for one complete writing session. The low seven hash
 * bits select a persistent shard; the remaining bits mix the complete key
 * because HestiaStore also uses this hash for mutation stripes and table
 * probes.
 */
final class RangeShardRouter implements ToIntFunction<Long> {

    static final int SHARD_COUNT = 128;
    private static final int SHARD_MASK = SHARD_COUNT - 1;

    private final long[] boundaries;

    /**
     * Creates ranges whose boundary keys belong to the range on their right.
     */
    RangeShardRouter(final long[] boundaries) {
        Objects.requireNonNull(boundaries, "boundaries");
        if (boundaries.length >= SHARD_COUNT) {
            throw new IllegalArgumentException("Too many shard boundaries");
        }
        this.boundaries = boundaries.clone();
        for (int index = 1; index < this.boundaries.length; index++) {
            if (this.boundaries[index] <= this.boundaries[index - 1]) {
                throw new IllegalArgumentException(
                        "Shard boundaries must be strictly increasing");
            }
        }
    }

    /**
     * Forecasts canonical successors of a bounded source sample, deduplicates
     * them and chooses quantile boundaries. No destination keys are observed,
     * and the resulting ranges never change during ingestion or maintenance.
     */
    static RangeShardRouter fromSourceSample(final RoundStateSample source,
            final PegSolitaireBoard board, final BoardSymmetry symmetry) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(symmetry, "symmetry");
        if (source.stateBitCount() != board.holeCount()) {
            throw new IllegalArgumentException(
                    "Source sample belongs to a different board");
        }
        final long[] states = source.states();
        // A board has at most four directed jumps originating at each hole.
        final long[] successors = new long[Math.multiplyExact(states.length,
                Math.multiplyExact(board.holeCount(), 4))];
        final int[] size = { 0 };
        final long[] transformed = new long[BoardSymmetry.TRANSFORM_COUNT];
        for (final long key : states) {
            symmetry.transformAll(key, transformed);
            board.generateSuccessors(key,
                    next -> successors[size[0]++] = symmetry
                            .canonicalizeMove(transformed, key ^ next));
        }
        Arrays.sort(successors, 0, size[0]);
        int uniqueCount = 0;
        for (int index = 0; index < size[0]; index++) {
            if (uniqueCount == 0
                    || successors[index] != successors[uniqueCount - 1]) {
                successors[uniqueCount++] = successors[index];
            }
        }
        final int boundaryCount = Math.min(SHARD_COUNT - 1,
                Math.max(0, uniqueCount - 1));
        final long[] selected = new long[boundaryCount];
        for (int index = 0; index < boundaryCount; index++) {
            selected[index] = successors[(int) ((long) (index + 1) * uniqueCount
                    / (boundaryCount + 1))];
        }
        return new RangeShardRouter(selected);
    }

    @Override
    public int applyAsInt(final Long key) {
        return hash(key.longValue());
    }

    /** Mixes every key bit, reserving the low bits for its range. */
    int hash(final long key) {
        long mixed = key;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return ((int) (mixed ^ (mixed >>> 32)) & ~SHARD_MASK) | shard(key);
    }

    /** Returns the range ID in [0, 127], including keys outside the sample. */
    int shard(final long key) {
        int low = 0;
        int high = boundaries.length;
        while (low < high) {
            final int middle = (low + high) >>> 1;
            if (key >= boundaries[middle]) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
