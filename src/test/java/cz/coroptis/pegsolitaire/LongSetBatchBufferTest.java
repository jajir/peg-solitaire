package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuLongSetWriting;
import org.hestiastore.index.senku.SenkuReady;
import org.junit.jupiter.api.Test;

class LongSetBatchBufferTest {

    @Test
    void fullAndPartialBuffersDrainExactlyOnceAndReuseOnlyAfterReturn() {
        for (final int count : new int[] { 0, 1, 3, 4, 5, 8, 9 }) {
            final BatchDestination destination = new BatchDestination();
            final LongSetBatchBuffer buffer = new LongSetBatchBuffer(
                    destination, new ExactRecentStateCache(0), 4);
            for (long key = 0; key < count; key++) {
                buffer.accept(key);
            }
            assertEquals(count / 4 * 4L, buffer.submittedKeys());
            buffer.flush();
            buffer.flush();
            assertEquals(count, buffer.submittedKeys());
            assertEquals((count + 3) / 4, destination.batches.size());
            assertArrayEquals(LongStream.range(0, count).toArray(),
                    destination.batches.stream().flatMapToLong(Arrays::stream)
                            .toArray());
            assertTrue(destination.batches.stream()
                    .allMatch(batch -> batch.length <= 4));
            if (destination.arrays.size() > 1) {
                assertSame(destination.arrays.get(0),
                        destination.arrays.get(1));
            }
        }
    }

    @Test
    void pendingDuplicatesDoNotBecomeCommittedUntilBatchReturns() {
        final BatchDestination destination = new BatchDestination();
        final ExactRecentStateCache cache = new ExactRecentStateCache(64);
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                cache, 4);
        final int hash = ExactRecentStateCache.mixedHash(0L);
        buffer.accept(0L);
        buffer.accept(0L);
        assertFalse(cache.contains(0L, hash));
        assertEquals(0, buffer.submittedKeys());
        assertTrue(destination.batches.isEmpty());
        destination.beforeWrite = () -> assertFalse(cache.contains(0L, hash));
        buffer.flush();
        assertTrue(cache.contains(0L, hash));
        assertEquals(1, buffer.submittedKeys());
        buffer.accept(0L);
        buffer.flush();
        assertEquals(1, destination.batches.size());
        assertArrayEquals(new long[] { 0L }, destination.batches.get(0));
    }

    @Test
    void pendingHashCollisionsNeverHideDifferentFullKeys() {
        final long collision = LongStream.range(1, 1000)
                .filter(key -> (ExactRecentStateCache.mixedHash(key)
                        & 7) == (ExactRecentStateCache.mixedHash(0L) & 7))
                .findFirst().orElseThrow();
        final BatchDestination destination = new BatchDestination();
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                new ExactRecentStateCache(1), 4);
        for (final long key : new long[] { 0L, collision, 0L, collision,
                Long.MIN_VALUE, Long.MAX_VALUE }) {
            buffer.accept(key);
        }
        buffer.flush();
        assertEquals(4, buffer.submittedKeys());
        assertEquals(Set.of(0L, collision, Long.MIN_VALUE, Long.MAX_VALUE),
                Set.copyOf(destination.batches.stream()
                        .flatMapToLong(Arrays::stream).boxed().toList()));
        buffer.accept(0L);
        buffer.flush();
        assertEquals(5, buffer.submittedKeys());
    }

    @Test
    void disabledCachePreservesDuplicateSubmissionCounts() {
        final BatchDestination destination = new BatchDestination();
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                new ExactRecentStateCache(0), 2);
        buffer.accept(0L);
        buffer.accept(0L);
        buffer.accept(0L);
        buffer.flush();
        assertEquals(3, buffer.submittedKeys());
        assertArrayEquals(new long[] { 0L, 0L }, destination.batches.get(0));
        assertArrayEquals(new long[] { 0L }, destination.batches.get(1));
    }

    @Test
    void nonPowerOfTwoChildCapacityStillHasExactBoundedPendingMembership() {
        final BatchDestination destination = new BatchDestination();
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                new ExactRecentStateCache(1), 3);
        for (final long key : new long[] { 0L, 1L, 0L, 2L, 3L, 4L }) {
            buffer.accept(key);
        }
        buffer.flush();
        assertEquals(5, buffer.submittedKeys());
        assertArrayEquals(new long[] { 0L, 1L, 2L },
                destination.batches.get(0));
        assertArrayEquals(new long[] { 3L, 4L }, destination.batches.get(1));
    }

    @Test
    void failedPartialBatchPreservesOldCacheAndPoisonsBuffer() {
        final BatchDestination destination = new BatchDestination();
        final ExactRecentStateCache cache = new ExactRecentStateCache(1);
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                cache, 4);
        buffer.accept(1L);
        buffer.flush();
        buffer.accept(2L);
        buffer.accept(3L);
        final IllegalStateException failure = new IllegalStateException(
                "non-prefix batch failure");
        destination.failure = failure;
        assertSame(failure,
                assertThrows(IllegalStateException.class, buffer::flush));
        assertEquals(1, buffer.submittedKeys());
        assertTrue(cache.contains(1L, ExactRecentStateCache.mixedHash(1L)));
        assertFalse(cache.contains(2L, ExactRecentStateCache.mixedHash(2L)));
        assertFalse(cache.contains(3L, ExactRecentStateCache.mixedHash(3L)));
        assertSame(failure,
                assertThrows(IllegalStateException.class, buffer::flush));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> buffer.accept(4L)));
        assertEquals(2, destination.arrays.size());
    }

    @Test
    void failureFromAutomaticFullFlushIsNotCountedOrRetried() {
        final BatchDestination destination = new BatchDestination();
        final AssertionError failure = new AssertionError("full batch failure");
        destination.error = failure;
        final LongSetBatchBuffer buffer = new LongSetBatchBuffer(destination,
                new ExactRecentStateCache(64), 1);
        assertSame(failure,
                assertThrows(AssertionError.class, () -> buffer.accept(0L)));
        assertEquals(0, buffer.submittedKeys());
        assertSame(failure, assertThrows(AssertionError.class, buffer::flush));
        assertEquals(1, destination.arrays.size());
    }

    @Test
    void rejectsUnboundedOrMissingConfiguration() {
        final BatchDestination destination = new BatchDestination();
        final ExactRecentStateCache cache = new ExactRecentStateCache(64);
        assertThrows(IllegalArgumentException.class,
                () -> new LongSetBatchBuffer(destination, cache, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new LongSetBatchBuffer(destination, cache, 4097));
        assertThrows(NullPointerException.class,
                () -> new LongSetBatchBuffer(null, cache, 4));
        assertThrows(NullPointerException.class,
                () -> new LongSetBatchBuffer(destination, null, 4));
    }

    private static final class BatchDestination implements SenkuLongSetWriting {
        private final List<long[]> batches = new ArrayList<>();
        private final List<long[]> arrays = new ArrayList<>();
        private Runnable beforeWrite = () -> {
        };
        private RuntimeException failure;
        private Error error;

        @Override
        public void putLong(final long key) {
            throw new AssertionError("Must use the batch API");
        }

        @Override
        public void putLongs(final long[] keys, final int offset,
                final int length) {
            assertEquals(0, offset);
            assertTrue(length > 0);
            arrays.add(keys);
            beforeWrite.run();
            if (failure != null) {
                throw failure;
            }
            if (error != null) {
                throw error;
            }
            batches.add(Arrays.copyOfRange(keys, offset, offset + length));
        }

        @Override
        public SenkuReady<Long, NullValue> finishWriting() {
            throw new UnsupportedOperationException();
        }
    }
}
