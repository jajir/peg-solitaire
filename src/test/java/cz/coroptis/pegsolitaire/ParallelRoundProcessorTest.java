package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

class ParallelRoundProcessorTest {

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
}
