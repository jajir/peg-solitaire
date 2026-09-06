package cz.coroptis.pegsolitaire;

import java.util.Arrays;

/**
 * Immutable bounded distribution of one unique round frontier. Legacy samples
 * have exact global ordinal spacing; weighted samples instead contain
 * approximate representatives whose positive weights sum to the exact count.
 * Neither representation is a membership structure.
 */
final class RoundStateSample {

    static final int MAX_SAMPLES = 4096;

    private final int stateBitCount;
    private final long stateCount;
    private final long stride;
    private final long[] states;
    private final long[] weights;

    /** Creates a validated snapshot; the supplied array is copied. */
    RoundStateSample(final int stateBitCount, final long stateCount,
            final long stride, final long[] states) {
        this(stateBitCount, stateCount, stride, states, null);
    }

    private RoundStateSample(final int stateBitCount, final long stateCount,
            final long stride, final long[] states, final long[] weighted) {
        if (stateBitCount < 1 || stateBitCount > Long.SIZE) {
            throw new IllegalArgumentException(
                    "stateBitCount must be between 1 and 64");
        }
        if (stateCount < 0 || (weighted == null
                && (stride < 1 || Long.bitCount(stride) != 1))) {
            throw new IllegalArgumentException(
                    "Invalid sample count or stride");
        }
        final long expected = weighted != null ? weighted.length
                : stateCount == 0 ? 0 : 1 + (stateCount - 1) / stride;
        if (states == null || states.length > MAX_SAMPLES
                || states.length != expected) {
            throw new IllegalArgumentException(
                    "Sample length does not match count and stride");
        }
        this.states = states.clone();
        for (int index = 0; index < this.states.length; index++) {
            validateState(this.states[index], stateBitCount);
            if (index > 0 && this.states[index] <= this.states[index - 1]) {
                throw new IllegalArgumentException(
                        "Sample states must be strictly increasing");
            }
        }
        this.stateBitCount = stateBitCount;
        this.stateCount = stateCount;
        this.stride = stride;
        weights = weighted == null ? new long[states.length] : weighted.clone();
        if (weighted == null) {
            Arrays.fill(weights, stride);
            if (weights.length > 0) {
                weights[weights.length - 1] = stateCount
                        - (weights.length - 1L) * stride;
            }
        }
        long total = 0L;
        for (final long weight : weights) {
            if (weight <= 0) {
                throw new IllegalArgumentException(
                        "Sample weights must be positive");
            }
            try {
                total = Math.addExact(total, weight);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Sample weight overflow",
                        overflow);
            }
        }
        if (total != stateCount) {
            throw new IllegalArgumentException(
                    "Sample weights must equal the exact state count");
        }
    }

    /** Creates an approximate weighted distribution, not an ordinal sample. */
    static RoundStateSample fromWeighted(final int stateBitCount,
            final long stateCount, final long[] states, final long[] weights) {
        if (weights == null) {
            throw new IllegalArgumentException("weights must not be null");
        }
        return new RoundStateSample(stateBitCount, stateCount, 0L, states,
                weights);
    }

    int stateBitCount() {
        return stateBitCount;
    }

    long stateCount() {
        return stateCount;
    }

    /**
     * Returns exact ordinal spacing, or zero for an approximate distribution.
     */
    long stride() {
        return stride;
    }

    boolean isWeighted() {
        return stride == 0L;
    }

    long[] weights() {
        return weights.clone();
    }

    long[] states() {
        return Arrays.copyOf(states, states.length);
    }

    /** Validates a board key without discarding any occupied position. */
    static void validateState(final long key, final int stateBitCount) {
        if (stateBitCount < Long.SIZE && (key >>> stateBitCount) != 0L) {
            throw new IllegalArgumentException(
                    "State contains bits outside the board");
        }
    }
}
