package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SortedStateSamplerTest {

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 4095, 4096, 4097, 8192, 8193, 100_000 })
    void retainsUniformRanksWithBoundedMemory(final int count) {
        final SortedStateSampler sampler = new SortedStateSampler(49);
        for (int index = 0; index < count; index++) {
            sampler.add(7L * index);
        }

        final RoundStateSample sample = sampler.snapshot();
        assertEquals(count, sample.stateCount());
        assertEquals(1, Long.bitCount(sample.stride()));
        final long[] states = sample.states();
        assertTrue(states.length <= RoundStateSample.MAX_SAMPLES);
        assertEquals(count == 0 ? 0 : 1 + (count - 1) / sample.stride(),
                states.length);
        for (int index = 0; index < states.length; index++) {
            assertEquals(7L * index * sample.stride(), states[index]);
        }
    }

    @Test
    void snapshotsAreIndependentOfFurtherSamplingAndReturnedArrays() {
        final SortedStateSampler sampler = new SortedStateSampler(49);
        sampler.add(1L);
        final RoundStateSample first = sampler.snapshot();
        first.states()[0] = 999L;
        sampler.add(3L);

        assertArrayEquals(new long[] { 1L }, first.states());
        assertArrayEquals(new long[] { 1L, 3L }, sampler.snapshot().states());
    }

    @Test
    void rejectsDuplicateUnsortedAndOutOfBoardStates() {
        final SortedStateSampler sampler = new SortedStateSampler(49);
        sampler.add(5L);

        assertThrows(IllegalArgumentException.class, () -> sampler.add(5L));
        assertThrows(IllegalArgumentException.class, () -> sampler.add(4L));
        assertThrows(IllegalArgumentException.class,
                () -> sampler.add(1L << 49));
        assertEquals(1L, sampler.snapshot().stateCount());
        sampler.add(1L << 48);
        assertEquals(2L, sampler.snapshot().stateCount());
    }

    @Test
    void supportsSignedLongOrderWhenAllBitsAreAllowed() {
        final SortedStateSampler sampler = new SortedStateSampler(64);
        sampler.add(Long.MIN_VALUE);
        sampler.add(0L);
        sampler.add(Long.MAX_VALUE);

        assertArrayEquals(new long[] { Long.MIN_VALUE, 0L, Long.MAX_VALUE },
                sampler.snapshot().states());
    }

    @Test
    void rejectsInvalidSnapshotMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new SortedStateSampler(0));
        assertThrows(IllegalArgumentException.class,
                () -> new SortedStateSampler(65));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, -1L, 1L, new long[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, 1L, 0L, new long[] { 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, 1L, 3L, new long[] { 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, 2L, 1L, new long[] { 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, 2L, 1L, new long[] { 2L, 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RoundStateSample(49, 1L, 1L,
                        new long[] { 1L << 49 }));
    }

    @Test
    void snapshotCopiesItsInput() {
        final long[] states = { 1L };
        final RoundStateSample sample = new RoundStateSample(49, 1L, 1L,
                states);
        states[0] = 2L;

        assertArrayEquals(new long[] { 1L }, sample.states());
    }
}
