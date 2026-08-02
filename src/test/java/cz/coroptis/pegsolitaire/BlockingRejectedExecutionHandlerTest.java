package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

class BlockingRejectedExecutionHandlerTest {

    @Test
    void fullQueueBlocksSubmissionUntilWorkerMakesSpace() throws Exception {
        final CountDownLatch firstTaskStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstTask = new CountDownLatch(1);
        final CountDownLatch thirdTaskFinished = new CountDownLatch(1);
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                new ParallelRoundProcessor.BlockingRejectedExecutionHandler());
        final ExecutorService submitter = Executors.newSingleThreadExecutor();
        try {
            workers.execute(() -> awaitRelease(firstTaskStarted,
                    releaseFirstTask));
            assertTrue(firstTaskStarted.await(5, TimeUnit.SECONDS));
            workers.execute(() -> {
            });

            final Future<?> blockedSubmission = submitter
                    .submit(() -> workers.execute(thirdTaskFinished::countDown));
            assertThrows(TimeoutException.class,
                    () -> blockedSubmission.get(100, TimeUnit.MILLISECONDS));

            releaseFirstTask.countDown();
            blockedSubmission.get(5, TimeUnit.SECONDS);
            assertTrue(thirdTaskFinished.await(5, TimeUnit.SECONDS));
            assertEquals(0, workers.getQueue().size());
        } finally {
            releaseFirstTask.countDown();
            submitter.shutdownNow();
            workers.shutdownNow();
            assertTrue(submitter.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private void awaitRelease(final CountDownLatch started,
            final CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
