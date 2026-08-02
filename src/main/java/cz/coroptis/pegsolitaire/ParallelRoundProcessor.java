package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;

/**
 * Reads source entries on its caller thread and processes one board per worker
 * task using a bounded fixed-thread executor.
 */
final class ParallelRoundProcessor {

    private final EnglishBoard board;
    private final BoardSymmetry symmetry;
    private final int workerCount;
    private final int queueCapacity;

    ParallelRoundProcessor(final EnglishBoard board,
            final BoardSymmetry symmetry, final int workerCount,
            final int queueCapacity) {
        this.board = board;
        this.symmetry = symmetry;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
    }

    ProcessingResult process(
            final Iterator<Entry<Long, NullValue>> sourceEntries,
            final SegmentIndex<Long, NullValue> destination) {
        final ThreadPoolExecutor executor = createExecutor();
        final ExecutorCompletionService<Integer> completions =
                new ExecutorCompletionService<>(executor);
        final int maximumInFlight = workerCount + queueCapacity;
        int inFlight = 0;
        long processedStates = 0L;
        long generatedMoves = 0L;
        boolean successful = false;
        try {
            while (sourceEntries.hasNext()) {
                final long state = sourceEntries.next().getKey();
                completions.submit(() -> processBoard(state, destination));
                inFlight++;
                if (inFlight == maximumInFlight) {
                    generatedMoves += waitForCompletedTask(completions);
                    processedStates++;
                    inFlight--;
                }
            }
            while (inFlight > 0) {
                generatedMoves += waitForCompletedTask(completions);
                processedStates++;
                inFlight--;
            }
            successful = true;
            return new ProcessingResult(processedStates, generatedMoves);
        } finally {
            shutdown(executor, successful);
        }
    }

    private int processBoard(final long state,
            final SegmentIndex<Long, NullValue> destination) {
        return board.generateSuccessors(state,
                successor -> destination.put(symmetry.canonicalize(successor),
                        NULL));
    }

    private int waitForCompletedTask(
            final ExecutorCompletionService<Integer> completions) {
        try {
            final Future<Integer> completed = completions.take();
            return completed.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for board counting task",
                    exception);
        } catch (ExecutionException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Board counting task failed", cause);
        }
    }

    private ThreadPoolExecutor createExecutor() {
        return new ThreadPoolExecutor(workerCount, workerCount, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                new WorkerThreadFactory(),
                new BlockingRejectedExecutionHandler());
    }

    private void shutdown(final ThreadPoolExecutor executor,
            final boolean successful) {
        if (successful) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }
        try {
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    throw new IllegalStateException(
                            "Board counting workers did not terminate");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping board counting workers",
                    exception);
        }
    }

    static final class ProcessingResult {

        private final long processedStates;
        private final long generatedMoves;

        private ProcessingResult(final long processedStates,
                final long generatedMoves) {
            this.processedStates = processedStates;
            this.generatedMoves = generatedMoves;
        }

        long processedStates() {
            return processedStates;
        }

        long generatedMoves() {
            return generatedMoves;
        }
    }

    static final class BlockingRejectedExecutionHandler
            implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(final Runnable task,
                final ThreadPoolExecutor executor) {
            while (!executor.isShutdown()) {
                try {
                    if (executor.getQueue().offer(task, 1, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException(
                            "Interrupted while waiting for worker queue space",
                            exception);
                }
            }
            throw new RejectedExecutionException(
                    "Board counting executor is shut down");
        }
    }

    private static final class WorkerThreadFactory implements ThreadFactory {

        private final AtomicInteger nextWorker = new AtomicInteger(1);

        @Override
        public Thread newThread(final Runnable task) {
            return new Thread(task,
                    "peg-solitaire-worker-" + nextWorker.getAndIncrement());
        }
    }
}
