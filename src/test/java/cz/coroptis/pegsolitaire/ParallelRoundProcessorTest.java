package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.LogManager;
import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;
import org.hestiastore.index.senku.SenkuLongSetWriting;
import org.junit.jupiter.api.Test;

class ParallelRoundProcessorTest {

    @Test
    void explicitlySelectedLongSetUsesBatchPutsWithoutTheBoxedBridge() {
        final AtomicLong puts = new AtomicLong();
        final SenkuLongSetWriting destination = new SenkuLongSetWriting() {
            @Override
            public void putLong(final long key) {
                throw new AssertionError(
                        "Primitive destination must receive batched puts");
            }

            @Override
            public void putLongs(final long[] keys, final int offset,
                    final int length) {
                puts.addAndGet(length);
            }

            @Override
            public void put(final Long key, final NullValue value) {
                throw new AssertionError(
                        "Primitive destination must not receive boxed puts");
            }

            @Override
            public SenkuReady<Long, NullValue> finishWriting() {
                throw new UnsupportedOperationException();
            }
        };
        final ParallelRoundProcessor.ProcessingResult result = processor(2, 4,
                0).process(repeatedInitialStates(9), destination);
        assertEquals(36L, result.generatedMoves());
        assertEquals(36L, result.submittedMoves());
        assertEquals(36L, puts.get());
    }

    @Test
    void countsAllFullAndPartialBatchesWithoutFiltering() {
        for (int count : new int[] { 0, 1, 3, 4, 5, 8, 9 }) {
            final CountingDestination destination = new CountingDestination();
            final ParallelRoundProcessor.ProcessingResult result = processor(1,
                    4, 0).process(repeatedInitialStates(count), destination);

            assertEquals(count, result.processedStates());
            assertEquals(4L * count, result.generatedMoves());
            assertEquals(4L * count, result.submittedMoves());
            assertEquals((count + 3) / 4, result.submittedTasks());
            assertEquals(4L * count, destination.puts.get());
        }
    }

    @Test
    void remembersExactDuplicatesAcrossBatchesWithoutChangingRawMoveCount() {
        final CountingDestination destination = new CountingDestination();
        final ParallelRoundProcessor.ProcessingResult result = processor(1, 4,
                64).process(repeatedInitialStates(9), destination);

        assertEquals(9L, result.processedStates());
        assertEquals(36L, result.generatedMoves());
        assertEquals(1L, result.submittedMoves());
        assertEquals(3L, result.submittedTasks());
        assertEquals(1L, destination.puts.get());
    }

    @Test
    void reusingProcessorNeverReusesCacheAcrossDestinations() {
        final ParallelRoundProcessor processor = processor(1, 4, 64);
        final CountingDestination first = new CountingDestination();
        final CountingDestination second = new CountingDestination();

        processor.process(repeatedInitialStates(9), first);
        processor.process(repeatedInitialStates(9), second);

        assertEquals(1L, first.puts.get());
        assertEquals(1L, second.puts.get());
        assertEquals(first.keys, second.keys);
    }

    @Test
    void parallelBatchesAndCollisionsPreserveEveryExpectedSuccessor() {
        final PegSolitaireBoard board = new EnglishBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        Set<Long> parents = Set.of(symmetry.canonicalize(board.initialState()));
        for (int round = 0; round < 5; round++) {
            parents = successors(board, symmetry, parents);
        }
        final Set<Long> expected = successors(board, symmetry, parents);
        final long expectedMoves = parents.stream()
                .mapToLong(state -> board.generateSuccessors(state, ignored -> {
                })).sum();
        final CountingDestination destination = new CountingDestination();

        final ParallelRoundProcessor.ProcessingResult result = processor(4, 7,
                1).process(entries(parents.stream().sorted().toList()),
                        destination);

        assertEquals(parents.size(), result.processedStates());
        assertEquals(expectedMoves, result.generatedMoves());
        assertEquals((parents.size() + 6) / 7, result.submittedTasks());
        assertEquals(destination.puts.get(), result.submittedMoves());
        assertEquals(expected, destination.keys);
    }

    @Test
    void writerFailureIsPropagatedAndProcessorCanBeReused() {
        final ParallelRoundProcessor processor = processor(2, 4, 64);
        final IllegalArgumentException failure = new IllegalArgumentException(
                "destination failed");
        final AtomicInteger activeCalls = new AtomicInteger();
        final CountingDestination failing = new CountingDestination() {
            @Override
            public void put(final Long state, final NullValue value) {
                activeCalls.incrementAndGet();
                try {
                    throw failure;
                } finally {
                    activeCalls.decrementAndGet();
                }
            }
        };

        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> processor.process(repeatedInitialStates(100), failing)));
        assertEquals(0, activeCalls.get());
        final CountingDestination recovered = new CountingDestination();
        assertEquals(4L, processor.process(repeatedInitialStates(1), recovered)
                .generatedMoves());
        assertEquals(1L, recovered.puts.get());
    }

    @Test
    void sourceFailureStopsWorkersAndIsPropagated() {
        final IllegalStateException failure = new IllegalStateException(
                "source failed");
        final Iterator<Entry<Long, NullValue>> source = new Iterator<>() {
            private int count;

            @Override
            public boolean hasNext() {
                if (count == 5) {
                    throw failure;
                }
                return true;
            }

            @Override
            public Entry<Long, NullValue> next() {
                count++;
                return Entry.of(EnglishBoard.INITIAL_STATE, NULL);
            }
        };

        assertSame(failure,
                assertThrows(IllegalStateException.class,
                        () -> processor(2, 4, 64).process(source,
                                new CountingDestination())));
    }

    @Test
    void callerInterruptionStopsWorkersAndPreservesInterruptFlag()
            throws Exception {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch releaseWrite = new CountDownLatch(1);
        final AtomicInteger activeWrites = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean callerInterrupted = new AtomicBoolean();
        final CountingDestination destination = new CountingDestination() {
            @Override
            public void put(final Long state, final NullValue value) {
                activeWrites.incrementAndGet();
                try {
                    writeStarted.countDown();
                    releaseWrite.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("write interrupted",
                            exception);
                } finally {
                    activeWrites.decrementAndGet();
                }
            }
        };
        final Thread caller = new Thread(() -> {
            try {
                processor(2, 4, 0).process(repeatedInitialStates(100),
                        destination);
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "interrupted-round-processor-test");
        try {
            caller.start();
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(caller.isAlive());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(callerInterrupted.get());
            assertEquals(0, activeWrites.get());
        } finally {
            releaseWrite.countDown();
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void rejectsInvalidWorkerBatchAndCacheConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> processor(0, 4, 64));
        assertThrows(IllegalArgumentException.class, () -> processor(1, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> processor(1, 4, 3));
        final PegSolitaireBoard board = new EnglishBoard();
        assertThrows(IllegalArgumentException.class,
                () -> new ParallelRoundProcessor(board,
                        new BoardSymmetry(board), 1, 0));
    }

    @Test
    void progressBecomesDueAfterSixtySeconds() {
        final long interval = ParallelRoundProcessor.PROGRESS_INTERVAL_NANOS;

        assertFalse(ParallelRoundProcessor.isProgressDue(interval - 1L, 0L));
        assertTrue(ParallelRoundProcessor.isProgressDue(interval, 0L));
    }

    @Test
    void progressLoggerHasInfoEnabled() {
        assertTrue(LogManager.getLogger(ParallelRoundProcessor.class)
                .isInfoEnabled());
    }

    @Test
    void completionPollingReportsProgressWhileLastTaskIsStillRunning()
            throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final ExecutorCompletionService<Integer> completions = new ExecutorCompletionService<>(
                executor);
        final CountDownLatch taskStarted = new CountDownLatch(1);
        final CountDownLatch releaseTask = new CountDownLatch(1);
        final AtomicInteger reports = new AtomicInteger();
        try {
            completions.submit(() -> {
                taskStarted.countDown();
                releaseTask.await();
                return 42;
            });
            assertTrue(taskStarted.await(5, TimeUnit.SECONDS));

            final int result = ParallelRoundProcessor.waitForCompletedTask(
                    completions, () -> TimeUnit.MILLISECONDS.toNanos(1), () -> {
                        reports.incrementAndGet();
                        releaseTask.countDown();
                    });

            assertEquals(42, result);
            assertTrue(reports.get() > 0,
                    "The blocked final task must not prevent progress reports");
        } finally {
            releaseTask.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cleanupTimeoutIsSuppressedOnTheOriginalFailure() {
        for (Throwable primaryFailure : new Throwable[] {
                new IllegalArgumentException("source failed"),
                new AssertionError("worker failed") }) {
            ParallelRoundProcessor.shutdown(nonTerminatingExecutor(), false,
                    primaryFailure, 1L);

            assertEquals(1, primaryFailure.getSuppressed().length);
            final Throwable cleanupFailure = primaryFailure.getSuppressed()[0];
            assertInstanceOf(IllegalStateException.class, cleanupFailure);
            assertEquals("Board counting workers did not terminate",
                    cleanupFailure.getMessage());
        }
    }

    @Test
    void cleanupTimeoutWithoutPrimaryFailureIsStillThrown() {
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> ParallelRoundProcessor
                        .shutdown(nonTerminatingExecutor(), true, null, 1L));

        assertEquals("Board counting workers did not terminate",
                failure.getMessage());
    }

    @Test
    void cleanupTimeoutRestoresCallerInterruptionAndPreservesPrimaryFailure() {
        final IllegalStateException primaryFailure = new IllegalStateException(
                "source interrupted");
        Thread.currentThread().interrupt();
        try {
            ParallelRoundProcessor.shutdown(nonTerminatingExecutor(), false,
                    primaryFailure, 1L);

            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, primaryFailure.getSuppressed().length);
        } finally {
            Thread.interrupted();
        }
    }

    private ThreadPoolExecutor nonTerminatingExecutor() {
        // No tasks or real threads are started: only termination reporting is
        // controlled, so a nanosecond deadline exercises timeout
        // deterministically.
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.NANOSECONDS,
                new ArrayBlockingQueue<>(1)) {
            @Override
            public boolean isTerminated() {
                return false;
            }
        };
    }

    private ParallelRoundProcessor processor(final int workers,
            final int batchSize, final int cacheCapacity) {
        final PegSolitaireBoard board = new EnglishBoard();
        return new ParallelRoundProcessor(board, new BoardSymmetry(board),
                workers, 2, batchSize, cacheCapacity);
    }

    private Iterator<Entry<Long, NullValue>> repeatedInitialStates(
            final int count) {
        return entries(Collections.nCopies(count, EnglishBoard.INITIAL_STATE));
    }

    private Iterator<Entry<Long, NullValue>> entries(final List<Long> states) {
        return states.stream().map(state -> Entry.of(state, NULL)).iterator();
    }

    private Set<Long> successors(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final Set<Long> parents) {
        final Set<Long> result = new TreeSet<>();
        parents.forEach(parent -> board.generateSuccessors(parent,
                successor -> result.add(symmetry.canonicalize(successor))));
        return result;
    }

    private static class CountingDestination
            implements SenkuWriting<Long, NullValue> {

        private final AtomicLong puts = new AtomicLong();
        private final Set<Long> keys = ConcurrentHashMap.newKeySet();

        @Override
        public void put(final Long state, final NullValue value) {
            assertSame(NULL, value);
            puts.incrementAndGet();
            keys.add(state);
        }

        @Override
        public SenkuReady<Long, NullValue> finishWriting() {
            throw new UnsupportedOperationException("in-memory test sink");
        }
    }
}
