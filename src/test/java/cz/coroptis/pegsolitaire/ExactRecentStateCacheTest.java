package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExactRecentStateCacheTest {

    @Test
    void zeroIsNotMistakenForAnOccupiedSlot() {
        final ExactRecentStateCache cache = new ExactRecentStateCache(64);
        final List<Long> submitted = new ArrayList<>();

        assertTrue(cache.submitIfAbsent(0L, submitted::add));
        assertFalse(cache.submitIfAbsent(0L, submitted::add));
        assertEquals(List.of(0L), submitted);
    }

    @Test
    void collisionsEvictButNeverHideDifferentCompleteKeys() {
        final ExactRecentStateCache cache = new ExactRecentStateCache(1);
        final List<Long> submitted = new ArrayList<>();
        final long[] states = { 0L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 48,
                0L };

        for (long state : states) {
            assertTrue(cache.submitIfAbsent(state, submitted::add));
            assertFalse(cache.submitIfAbsent(state, submitted::add));
        }
        assertEquals(List.of(0L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 48, 0L),
                submitted);
    }

    @Test
    void failedSubmissionNeitherCachesNewKeyNorEvictsSuccessfulKey() {
        final ExactRecentStateCache cache = new ExactRecentStateCache(1);
        final List<Long> submitted = new ArrayList<>();
        final IllegalStateException failure = new IllegalStateException("put");
        assertTrue(cache.submitIfAbsent(1L, submitted::add));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cache.submitIfAbsent(2L, ignored -> {
                    throw failure;
                })));

        assertFalse(cache.submitIfAbsent(1L, submitted::add));
        assertTrue(cache.submitIfAbsent(2L, submitted::add));
        assertEquals(List.of(1L, 2L), submitted);
    }

    @Test
    void zeroCapacityDisablesFiltering() {
        final ExactRecentStateCache cache = new ExactRecentStateCache(0);
        final List<Long> submitted = new ArrayList<>();

        assertTrue(cache.submitIfAbsent(0L, submitted::add));
        assertTrue(cache.submitIfAbsent(0L, submitted::add));
        assertEquals(List.of(0L, 0L), submitted);
    }

    @Test
    void rejectsInvalidCapacities() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecentStateCache(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecentStateCache(3));
    }
}
