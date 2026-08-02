package cz.coroptis.pegsolitaire;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.time.Duration;

import org.apache.commons.cli.ParseException;

/**
 * Unified command-line entry point for counting and statistics.
 */
public final class PegSolitaireMain {

    private PegSolitaireMain() {
    }

    /**
     * Parses and executes one command.
     *
     * @param args command and options
     */
    public static void main(final String[] args) {
        final int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(final String[] args, final PrintStream output,
            final PrintStream error) {
        if (PegSolitaireCli.isHelpRequest(args)) {
            PegSolitaireCli.printHelp(new PrintWriter(output, true));
            return 0;
        }

        final CliConfiguration configuration;
        try {
            configuration = PegSolitaireCli.parse(args);
        } catch (ParseException exception) {
            error.println("Command-line error: " + exception.getMessage());
            error.println("Use --help to see valid commands and options.");
            return 2;
        }

        try {
            if (configuration.action() == CliConfiguration.Action.STATS) {
                new RoundStatisticsReporter(configuration.directory())
                        .print(output);
            } else {
                count(configuration, output);
            }
            return 0;
        } catch (Exception exception) {
            error.println(commandName(configuration) + " failed: "
                    + exception.getMessage());
            exception.printStackTrace(error);
            return 1;
        }
    }

    private static void count(final CliConfiguration configuration,
            final PrintStream output) throws Exception {
        final long started = System.nanoTime();
        output.println("Peg solitaire board: "
                + configuration.board().optionValue());
        output.println("Peg solitaire data root: "
                + configuration.directory());
        output.printf("Workers: %d, queue capacity: %d%n",
                configuration.workerCount(), configuration.queueCapacity());
        try {
            final RoundResult result = new RoundEnumerator(
                    configuration.directory(), configuration.workerCount(),
                    configuration.queueCapacity()).runOneRound();
            printResult(result, elapsed(started), output);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "round failed after " + elapsed(started), exception);
        }
    }

    private static String commandName(final CliConfiguration configuration) {
        return configuration.action() == CliConfiguration.Action.COUNT
                ? "Count" : "Statistics";
    }

    private static void printResult(final RoundResult result,
            final Duration elapsed, final PrintStream output) {
        if (result.isInitialized()) {
            output.printf("Initialized round 1: unique states=1, elapsed=%s%n",
                    elapsed);
            return;
        }
        if (result.isTerminal()) {
            output.printf(
                    "Round %d is empty; search is complete, elapsed=%s%n",
                    result.sourceRound(), elapsed);
            return;
        }
        output.printf(
                "Completed round %d -> %d: processed=%d, generated moves=%d, unique states=%d, elapsed=%s%n",
                result.sourceRound(), result.destinationRound(),
                result.processedStates(), result.generatedMoves(),
                result.uniqueStates(), elapsed);
    }

    private static Duration elapsed(final long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
