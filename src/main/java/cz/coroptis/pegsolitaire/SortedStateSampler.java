package cz.coroptis.pegsolitaire;

import java.util.Arrays;

/**
 * Collects a deterministic quantile sample in one pass over sorted unique keys.
 * Once full, it retains every other sample and doubles the sampling stride.
 * Memory stays at 32 KiB regardless of the frontier size. Caller-confined.
 */
final class SortedStateSampler {

    private final int stateBitCount;
    private final long[] samples = new long[RoundStateSample.MAX_SAMPLES];
    private int size;
    private long count;
    private long stride = 1L;
    private long previous;

    SortedStateSampler(final int stateBitCount) {
        if (stateBitCount < 1 || stateBitCount > Long.SIZE) {
            throw new IllegalArgumentException(
                    "stateBitCount must be between 1 and 64");
        }
        this.stateBitCount = stateBitCount;
    }

    /** Records a sorted unique state, rejecting count overflow or bad order. */
    void add(final long key) {
        RoundStateSample.validateState(key, stateBitCount);
        if (count > 0 && key <= previous) {
            throw new IllegalArgumentException(
                    "Round states must be strictly increasing");
        }
        if (count == Long.MAX_VALUE) {
            throw new IllegalStateException("Round state count overflow");
        }
        if ((count & (stride - 1)) == 0) {
            if (size == samples.length) {
                for (int index = 0; index < size / 2; index++) {
                    samples[index] = samples[index * 2];
                }
                size /= 2;
                stride = Math.multiplyExact(stride, 2L);
            }
            if ((count & (stride - 1)) == 0) {
                samples[size++] = key;
            }
        }
        previous = key;
        count++;
    }

    /** Returns an immutable snapshot without disturbing continued sampling. */
    RoundStateSample snapshot() {
        return new RoundStateSample(stateBitCount, count, stride,
                Arrays.copyOf(samples, size));
    }
}
