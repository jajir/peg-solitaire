package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.util.Iterator;
import java.util.Objects;
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
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuWriting;
import org.hestiastore.index.senku.SenkuLongSetWriting;

/**
 * Reads source entries on its caller thread and processes bounded primitive
 * batches using a fixed-thread executor. Each worker filters only exact recent
 * duplicate outputs; the persistent store still performs complete
 * deduplication.
 */
final class ParallelRoundProcessor {

    static final long PROGRESS_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
    static final int DEFAULT_BATCH_SIZE = 4096;
    static final int DEFAULT_CACHE_CAPACITY = 65_536;

    private static final Logger LOGGER = LogManager
            .getLogger(ParallelRoundProcessor.class);

    private final CompiledBoardKernel kernel;
    private final int workerCount;
    private final int queueCapacity;
    private final int batchSize;
    private final int cacheCapacity;

    ParallelRoundProcessor(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final int workerCount,
            final int queueCapacity) {
        this(board, symmetry, workerCount, queueCapacity, DEFAULT_BATCH_SIZE,
                DEFAULT_CACHE_CAPACITY);
    }

    /**
     * Package-private tuning constructor for controlled benchmarks and tests.
     */
    ParallelRoundProcessor(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final int workerCount,
            final int queueCapacity, final int batchSize,
            final int cacheCapacity) {
        if (workerCount < 1 || queueCapacity < 1 || batchSize < 1
                || (long) workerCount + queueCapacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "worker count, queue capacity and batch size must be positive"
                            + " and the in-flight limit must fit an integer");
        }
        if (cacheCapacity < 0 || (cacheCapacity & (cacheCapacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "cache capacity must be zero or a positive power of two");
        }
        kernel = CompiledBoardKernel.create(board, symmetry);
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.cacheCapacity = cacheCapacity;
    }

    ProcessingResult process(
            final Iterator<Entry<Long, NullValue>> sourceEntries,
            final SenkuWriting<Long, NullValue> destination) {
        Objects.requireNonNull(sourceEntries, "sourceEntries");
        Objects.requireNonNull(destination, "destination");
        checkInterrupted();
        final ThreadPoolExecutor executor = createExecutor();
        // New contexts and new worker threads for every output round: cached
        // writes must never leak into another destination's deduplication.
        final ThreadLocal<WorkerContext> contexts = ThreadLocal.withInitial(
                () -> new WorkerContext(destination, cacheCapacity));
        final ExecutorCompletionService<ProcessingResult> completions = new ExecutorCompletionService<>(
                executor);
        final int maximumInFlight = workerCount + queueCapacity;
        int inFlight = 0;
        final Progress progress = new Progress();
        long generatedMoves = 0L;
        long submittedMoves = 0L;
        long submittedTasks = 0L;
        boolean successful = false;
        Throwable failure = null;
        try {
            while (sourceEntries.hasNext()) {
                checkInterrupted();
                final long[] states = new long[batchSize];
                int size = 0;
                do {
                    states[size++] = sourceEntries.next().getKey();
                } while (size < batchSize && sourceEntries.hasNext());
                checkInterrupted();
                final int stateCount = size;
                completions.submit(
                        () -> processBatch(states, stateCount, contexts.get()));
                inFlight++;
                progress.submittedStates += stateCount;
                submittedTasks++;
                progress.logIfDue();
                if (inFlight == maximumInFlight) {
                    final ProcessingResult result = waitForCompletedTask(
                            completions, progress::nanosUntilNextLog,
                            progress::logIfDue);
                    generatedMoves += result.generatedMoves();
                    submittedMoves += result.submittedMoves();
                    progress.processedStates += result.processedStates();
                    progress.logIfDue();
                    inFlight--;
                }
            }
            while (inFlight > 0) {
                final ProcessingResult result = waitForCompletedTask(
                        completions, progress::nanosUntilNextLog,
                        progress::logIfDue);
                generatedMoves += result.generatedMoves();
                submittedMoves += result.submittedMoves();
                progress.processedStates += result.processedStates();
                progress.logIfDue();
                inFlight--;
            }
            successful = true;
            return new ProcessingResult(progress.processedStates,
                    generatedMoves, submittedMoves, submittedTasks);
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            shutdown(executor, successful, failure,
                    TimeUnit.MINUTES.toNanos(1));
        }
    }

    static boolean isProgressDue(final long now, final long lastProgressLog) {
        return now - lastProgressLog >= PROGRESS_INTERVAL_NANOS;
    }

    private ProcessingResult processBatch(final long[] states,
            final int stateCount, final WorkerContext context) {
        final long previousSubmittedMoves = context.submittedMoves;
        long generatedMoves = 0L;
        for (int index = 0; index < stateCount; index++) {
            checkInterrupted();
            generatedMoves += kernel.generateCanonicalSuccessors(states[index],
                    context.transformedParent, context);
        }
        return new ProcessingResult(stateCount, generatedMoves,
                context.submittedMoves - previousSubmittedMoves, 1L);
    }

    /**
     * Uses a supplied deadline so progress remains visible during final drain.
     */
    static <T> T waitForCompletedTask(
            final ExecutorCompletionService<T> completions,
            final LongSupplier nanosUntilProgress,
            final Runnable reportProgress) {
        try {
            while (true) {
                final Future<T> completed = completions.poll(
                        Math.max(1L, nanosUntilProgress.getAsLong()),
                        TimeUnit.NANOSECONDS);
                if (completed != null) {
                    return completed.get();
                }
                reportProgress.run();
            }
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
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Board counting task failed",
                    cause);
        }
    }

    private ThreadPoolExecutor createExecutor() {
        return new ThreadPoolExecutor(workerCount, workerCount, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                new WorkerThreadFactory(),
                new BlockingRejectedExecutionHandler());
    }

    /** Preserves a primary failure if bounded worker cleanup also fails. */
    static void shutdown(final ThreadPoolExecutor executor,
            final boolean successful, final Throwable primaryFailure,
            final long terminationTimeoutNanos) {
        try {
            stopWorkers(executor, successful, terminationTimeoutNanos);
        } catch (RuntimeException | Error shutdownFailure) {
            if (primaryFailure == null) {
                throw shutdownFailure;
            }
            if (shutdownFailure != primaryFailure) {
                primaryFailure.addSuppressed(shutdownFailure);
            }
        }
    }

    private static void stopWorkers(final ThreadPoolExecutor executor,
            final boolean successful, final long terminationTimeoutNanos) {
        if (successful) {
            executor.shutdown();
        } else {
            executor.shutdownNow();
        }
        // An interrupted caller must still wait until workers stop writing.
        // Restore its flag after the bounded termination wait instead of
        // immediately abandoning that wait because the flag was already set.
        boolean interrupted = Thread.interrupted();
        final long deadline = System.nanoTime() + terminationTimeoutNanos;
        try {
            while (!executor.isTerminated()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    executor.shutdownNow();
                    throw new IllegalStateException(
                            "Board counting workers did not terminate");
                }
                try {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    executor.shutdownNow();
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Board counting was interrupted");
        }
    }

    /**
     * Caller-confined counters and a single progress deadline for all phases.
     */
    private static final class Progress {

        private long submittedStates;
        private long processedStates;
        private long lastProgressLog = System.nanoTime();

        private long nanosUntilNextLog() {
            return Math.max(1L, PROGRESS_INTERVAL_NANOS
                    - (System.nanoTime() - lastProgressLog));
        }

        private void logIfDue() {
            final long now = System.nanoTime();
            if (isProgressDue(now, lastProgressLog)) {
                LOGGER.info("Processed {} of {} submitted states",
                        processedStates, submittedStates);
                lastProgressLog = now;
            }
        }
    }

    private static final class WorkerContext implements LongConsumer {

        private final long[] transformedParent = new long[BoardSymmetry.TRANSFORM_COUNT];
        private final ExactRecentStateCache cache;
        private final LongConsumer submit;
        private long submittedMoves;

        private WorkerContext(final SenkuWriting<Long, NullValue> destination,
                final int cacheCapacity) {
            cache = new ExactRecentStateCache(cacheCapacity);
            submit = destination instanceof SenkuLongSetWriting
                    ? ((SenkuLongSetWriting) destination)::putLong
                    : state -> destination.put(state, NULL);
        }

        @Override
        public void accept(final long state) {
            if (cache.submitIfAbsent(state, submit)) {
                submittedMoves++;
            }
        }
    }

    static final class ProcessingResult {

        private final long processedStates;
        private final long generatedMoves;
        private final long submittedMoves;
        private final long submittedTasks;

        private ProcessingResult(final long processedStates,
                final long generatedMoves, final long submittedMoves,
                final long submittedTasks) {
            this.processedStates = processedStates;
            this.generatedMoves = generatedMoves;
            this.submittedMoves = submittedMoves;
            this.submittedTasks = submittedTasks;
        }

        long processedStates() {
            return processedStates;
        }

        long generatedMoves() {
            return generatedMoves;
        }

        long submittedMoves() {
            return submittedMoves;
        }

        long submittedTasks() {
            return submittedTasks;
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
