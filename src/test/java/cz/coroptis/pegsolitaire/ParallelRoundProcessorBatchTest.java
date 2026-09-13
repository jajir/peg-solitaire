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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuLongSetWriting;
import org.hestiastore.index.senku.SenkuReady;
import org.junit.jupiter.api.Test;

class ParallelRoundProcessorBatchTest {

    @Test
    void childBufferBoundaryIsIndependentOfParentTaskSize() {
        final BatchDestination destination = new BatchDestination(false);
        final ParallelRoundProcessor.ProcessingResult result = processor(1,
                2048, 0).process(repeatedParents(1025), destination);
        assertEquals(1025, result.processedStates());
        assertEquals(4100, result.generatedMoves());
        assertEquals(4100, result.submittedMoves());
        assertEquals(1, result.submittedTasks());
        assertEquals(List.of(4096, 4), List.copyOf(destination.lengths));
        assertEquals(4100, destination.puts.get());
    }

    @Test
    void everyTaskDrainsItsPartialChildBufferBeforeReportingCompletion() {
        final BatchDestination destination = new BatchDestination(false);
        final ParallelRoundProcessor.ProcessingResult result = processor(1, 4,
                0).process(repeatedParents(9), destination);
        assertEquals(9, result.processedStates());
        assertEquals(36, result.generatedMoves());
        assertEquals(36, result.submittedMoves());
        assertEquals(3, result.submittedTasks());
        assertEquals(List.of(16, 16, 4), List.copyOf(destination.lengths));
    }

    @Test
    void pendingAndCommittedDuplicatesPreserveRawMovesAndResetForNewDestination() {
        final ParallelRoundProcessor processor = processor(1, 4, 64);
        final BatchDestination first = new BatchDestination(false);
        final BatchDestination second = new BatchDestination(false);
        final ParallelRoundProcessor.ProcessingResult result = processor
                .process(repeatedParents(9), first);
        final ParallelRoundProcessor.ProcessingResult repeated = processor
                .process(repeatedParents(9), second);
        assertEquals(36, result.generatedMoves());
        assertEquals(1, result.submittedMoves());
        assertEquals(1, repeated.submittedMoves());
        assertEquals(List.of(1), List.copyOf(first.lengths));
        assertEquals(List.of(1), List.copyOf(second.lengths));
        assertEquals(first.keys, second.keys);
    }

    @Test
    void zeroParentsAndParentsWithNoChildrenDoNotCallDestination() {
        for (final List<Long> parents : List.of(List.<Long>of(), List.of(0L))) {
            final BatchDestination destination = new BatchDestination(false);
            final ParallelRoundProcessor.ProcessingResult result = processor(2,
                    4, 64).process(entries(parents), destination);
            assertEquals(parents.size(), result.processedStates());
            assertEquals(0, result.generatedMoves());
            assertEquals(0, result.submittedMoves());
            assertTrue(destination.lengths.isEmpty());
        }
    }

    @Test
    void reorderedParallelBatchCallsRetainEveryExpectedChildAndRawMove() {
        final PegSolitaireBoard board = new EnglishBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        Set<Long> parents = Set.of(symmetry.canonicalize(board.initialState()));
        for (int round = 0; round < 6; round++) {
            parents = successors(board, symmetry, parents);
        }
        assertTrue(parents.size() > 14);
        final Set<Long> expected = successors(board, symmetry, parents);
        final long rawMoves = parents.stream().mapToLong(
                parent -> board.generateSuccessors(parent, ignored -> {
                })).sum();
        final BatchDestination destination = new BatchDestination(true);
        final ParallelRoundProcessor.ProcessingResult result = processor(4, 7,
                1).process(entries(parents.stream().sorted().toList()),
                        destination);
        assertEquals(parents.size(), result.processedStates());
        assertEquals(rawMoves, result.generatedMoves());
        assertEquals(expected, destination.keys);
        assertEquals(destination.puts.get(), result.submittedMoves());
        assertEquals((parents.size() + 6) / 7, result.submittedTasks());
        assertEquals(0, destination.secondCompleted.getCount());
    }

    @Test
    void partialAndFullBatchFailuresPropagateWithoutACompletedTaskResult() {
        for (final int parents : new int[] { 1, 1024 }) {
            final IllegalStateException failure = new IllegalStateException(
                    "batch put");
            final AtomicInteger calls = new AtomicInteger();
            final BatchDestination destination = new BatchDestination(false) {
                @Override
                public void putLongs(final long[] keys, final int offset,
                        final int length) {
                    calls.incrementAndGet();
                    throw failure;
                }
            };
            final ParallelRoundProcessor processor = processor(1, 2048, 0);
            assertSame(failure,
                    assertThrows(IllegalStateException.class, () -> processor
                            .process(repeatedParents(parents), destination)));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void interruptedCallerWaitsForBlockedPrimitiveBatchWriterToStop()
            throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch stopped = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean callerInterrupted = new AtomicBoolean();
        final BatchDestination destination = new BatchDestination(false) {
            @Override
            public void putLongs(final long[] keys, final int offset,
                    final int length) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Batch writer interrupted",
                            exception);
                } finally {
                    stopped.countDown();
                }
            }
        };
        final Thread caller = new Thread(() -> {
            try {
                processor(1, 1, 64).process(repeatedParents(1), destination);
            } catch (RuntimeException | Error exception) {
                failure.set(exception);
            } finally {
                callerInterrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "batch-caller-interruption-test");
        caller.start();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(caller.isAlive());
            assertEquals(0, stopped.getCount());
            assertInstanceOf(IllegalStateException.class, failure.get());
            assertTrue(callerInterrupted.get());
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private static ParallelRoundProcessor processor(final int workers,
            final int parentBatchSize, final int cacheCapacity) {
        final PegSolitaireBoard board = new EnglishBoard();
        return new ParallelRoundProcessor(board, new BoardSymmetry(board),
                workers, 2, parentBatchSize, cacheCapacity);
    }

    private static Iterator<Entry<Long, NullValue>> repeatedParents(
            final int count) {
        return entries(Collections.nCopies(count, EnglishBoard.INITIAL_STATE));
    }

    private static Iterator<Entry<Long, NullValue>> entries(
            final List<Long> parents) {
        return parents.stream().map(key -> Entry.of(key, NULL)).iterator();
    }

    private static Set<Long> successors(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final Set<Long> parents) {
        final Set<Long> result = new TreeSet<>();
        parents.forEach(parent -> board.generateSuccessors(parent,
                child -> result.add(symmetry.canonicalize(child))));
        return result;
    }

    private static class BatchDestination implements SenkuLongSetWriting {
        private final ConcurrentLinkedQueue<Integer> lengths = new ConcurrentLinkedQueue<>();
        private final Set<Long> keys = ConcurrentHashMap.newKeySet();
        private final AtomicLong puts = new AtomicLong();
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondCompleted = new CountDownLatch(1);
        private final boolean reorder;

        private BatchDestination(final boolean reorder) {
            this.reorder = reorder;
        }

        @Override
        public void putLong(final long key) {
            throw new AssertionError(
                    "Batch destination must not receive single puts");
        }

        @Override
        public void put(final Long key, final NullValue value) {
            throw new AssertionError(
                    "Batch destination must not receive boxed puts");
        }

        @Override
        public void putLongs(final long[] values, final int offset,
                final int length) {
            assertEquals(0, offset);
            assertTrue(length > 0 && length <= 4096);
            final int call = calls.incrementAndGet();
            if (reorder && call == 1) {
                firstEntered.countDown();
                await(secondCompleted);
            } else if (reorder && call == 2) {
                await(firstEntered);
            }
            for (int index = offset; index < offset + length; index++) {
                keys.add(values[index]);
            }
            puts.addAndGet(length);
            lengths.add(length);
            if (reorder && call == 2) {
                secondCompleted.countDown();
            }
        }

        @Override
        public SenkuReady<Long, NullValue> finishWriting() {
            throw new UnsupportedOperationException();
        }

        private static void await(final CountDownLatch latch) {
            try {
                assertTrue(latch.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Batch test interrupted",
                        exception);
            }
        }
    }
}
