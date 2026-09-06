package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoundStateSampleTest {

    @Test
    void distinguishesWeightedRepresentativesFromExactOrdinalSamples() {
        final RoundStateSample ordinal = new RoundStateSample(49, 3, 2,
                new long[] { 3, 7 });
        assertFalse(ordinal.isWeighted());
        assertArrayEquals(new long[] { 2, 1 }, ordinal.weights());
        final long[] states = { 3, 7 };
        final long[] weights = { 90, 10 };
        final RoundStateSample weighted = RoundStateSample.fromWeighted(49, 100,
                states, weights);
        states[0] = 9;
        weights[0] = 1;
        weighted.states()[0] = 99;
        weighted.weights()[0] = 99;
        assertTrue(weighted.isWeighted());
        assertEquals(0, weighted.stride());
        assertEquals(100, weighted.stateCount());
        assertArrayEquals(new long[] { 3, 7 }, weighted.states());
        assertArrayEquals(new long[] { 90, 10 }, weighted.weights());
    }

    @Test
    void rejectsInvalidWeightedCountDomainOrderAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> RoundStateSample
                .fromWeighted(49, 1, new long[] { 1 }, new long[] { 2 }));
        assertThrows(IllegalArgumentException.class, () -> RoundStateSample
                .fromWeighted(49, 1, new long[] { 1 }, new long[] { 0 }));
        assertThrows(IllegalArgumentException.class,
                () -> RoundStateSample.fromWeighted(49, 1,
                        new long[] { 1L << 50 }, new long[] { 1 }));
        assertThrows(IllegalArgumentException.class, () -> RoundStateSample
                .fromWeighted(49, 2, new long[] { 2, 1 }, new long[] { 1, 1 }));
        assertThrows(IllegalArgumentException.class,
                () -> RoundStateSample.fromWeighted(49, Long.MAX_VALUE,
                        new long[] { 1, 2 }, new long[] { Long.MAX_VALUE, 1 }));
        assertEquals(0, RoundStateSample
                .fromWeighted(49, 0, new long[0], new long[0]).stateCount());
    }
}
