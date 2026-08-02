package cz.coroptis.pegsolitaire;

import java.time.Duration;

/**
 * Command-line entry point for one peg-solitaire enumeration round.
 */
public final class PegSolitaireMain {

    private PegSolitaireMain() {
    }

    /**
     * Runs one initialization or counting round using the hardcoded data root.
     *
     * @param args must be empty
     */
    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("This application accepts no arguments.");
            System.exit(2);
        }
        final long started = System.nanoTime();
        System.out.println("Peg solitaire data root: "
                + ApplicationConfiguration.DATA_ROOT);
        try {
            final RoundResult result = new RoundEnumerator(
                    ApplicationConfiguration.DATA_ROOT).runOneRound();
            printResult(result, elapsed(started));
        } catch (Exception exception) {
            System.err.println("Round failed after " + elapsed(started) + ": "
                    + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void printResult(final RoundResult result,
            final Duration elapsed) {
        if (result.isInitialized()) {
            System.out.printf("Initialized round 1: unique states=1, elapsed=%s%n",
                    elapsed);
            return;
        }
        if (result.isTerminal()) {
            System.out.printf("Round %d is empty; search is complete, elapsed=%s%n",
                    result.sourceRound(), elapsed);
            return;
        }
        System.out.printf(
                "Completed round %d -> %d: processed=%d, generated moves=%d, unique states=%d, elapsed=%s%n",
                result.sourceRound(), result.destinationRound(),
                result.processedStates(), result.generatedMoves(),
                result.uniqueStates(), elapsed);
    }

    private static Duration elapsed(final long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
