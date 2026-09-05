package cz.coroptis.pegsolitaire;

import java.util.Arrays;

/**
 * Immutable, evenly spaced sample of one sorted, unique round frontier. The
 * sample is small enough to persist alongside a completed index and use to
 * forecast the next round's key distribution.
 */
final class RoundStateSample {

    static final int MAX_SAMPLES = 4096;

    private final int stateBitCount;
    private final long stateCount;
    private final long stride;
    private final long[] states;

    /** Creates a validated snapshot; the supplied array is copied. */
    RoundStateSample(final int stateBitCount, final long stateCount,
            final long stride, final long[] states) {
        if (stateBitCount < 1 || stateBitCount > Long.SIZE) {
            throw new IllegalArgumentException(
                    "stateBitCount must be between 1 and 64");
        }
        if (stateCount < 0 || stride < 1 || Long.bitCount(stride) != 1) {
            throw new IllegalArgumentException(
                    "Invalid sample count or stride");
        }
        final long expected = stateCount == 0 ? 0
                : 1 + (stateCount - 1) / stride;
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
    }

    int stateBitCount() {
        return stateBitCount;
    }

    long stateCount() {
        return stateCount;
    }

    long stride() {
        return stride;
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
