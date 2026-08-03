package cz.coroptis.pegsolitaire;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.help.TextHelpAppendable;

/**
 * Parses and documents the peg-solitaire command line.
 */
final class PegSolitaireCli {

    private static final int MAX_WORKERS = 256;
    private static final int MAX_QUEUE_CAPACITY = 1_000_000;

    private PegSolitaireCli() {
    }

    static CliConfiguration parse(final String[] arguments)
            throws ParseException {
        if (arguments == null || arguments.length == 0) {
            throw new ParseException("Missing command: expected count or stats");
        }
        final CliConfiguration.Action action = parseAction(arguments[0]);
        final Options options = optionsFor(action);
        final String[] optionArguments = Arrays.copyOfRange(arguments, 1,
                arguments.length);
        final CommandLine commandLine = new DefaultParser().parse(options,
                optionArguments);
        if (!commandLine.getArgList().isEmpty()) {
            throw new ParseException(
                    "Unexpected arguments: " + commandLine.getArgList());
        }

        final BoardVariant board = BoardVariant
                .parse(commandLine.getOptionValue("board"));
        final Path directory = parseDirectory(
                commandLine.getOptionValue("directory"));
        if (action == CliConfiguration.Action.STATS) {
            return new CliConfiguration(action, board, directory, 0, 0);
        }
        final int workers = parsePositiveInt(
                commandLine.getOptionValue("workers"), "workers", MAX_WORKERS);
        final int queueCapacity = parsePositiveInt(
                commandLine.getOptionValue("queue-capacity"), "queue-capacity",
                MAX_QUEUE_CAPACITY);
        return new CliConfiguration(action, board, directory, workers,
                queueCapacity);
    }

    static boolean isHelpRequest(final String[] arguments) {
        if (arguments == null) {
            return false;
        }
        return Arrays.stream(arguments)
                .anyMatch(argument -> "--help".equals(argument)
                        || "-h".equals(argument));
    }

    static void printHelp(final PrintWriter output) {
        final TextHelpAppendable helpOutput = new TextHelpAppendable(output);
        helpOutput.setMaxWidth(100);
        final HelpFormatter formatter = HelpFormatter.builder()
                .setHelpAppendable(helpOutput)
                .setShowSince(false)
                .get();
        output.println("Peg Solitaire Round Enumerator");
        output.println();
        try {
            formatter.printHelp("peg-solitaire count", null, countOptions(),
                    null, true);
            output.println();
            formatter.printHelp("peg-solitaire stats", null, statsOptions(),
                    null, true);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to print command help",
                    exception);
        }
        output.flush();
    }

    private static CliConfiguration.Action parseAction(final String action)
            throws ParseException {
        if ("count".equalsIgnoreCase(action)) {
            return CliConfiguration.Action.COUNT;
        }
        if ("stats".equalsIgnoreCase(action)) {
            return CliConfiguration.Action.STATS;
        }
        throw new ParseException("Unknown command: " + action);
    }

    private static Options optionsFor(final CliConfiguration.Action action) {
        return action == CliConfiguration.Action.COUNT ? countOptions()
                : statsOptions();
    }

    private static Options countOptions() {
        return commonOptions()
                .addOption(requiredOption("w", "workers", "number",
                        "number of board-processing worker threads"))
                .addOption(requiredOption("q", "queue-capacity", "number",
                        "maximum number of queued board tasks"));
    }

    private static Options statsOptions() {
        return commonOptions();
    }

    private static Options commonOptions() {
        return new Options()
                .addOption(requiredOption("b", "board", "name",
                        "board implementation: english, european, or senku"))
                .addOption(requiredOption("d", "directory", "path",
                        "absolute persistent round directory"))
                .addOption(Option.builder("h").longOpt("help")
                        .desc("print command help").get());
    }

    private static Option requiredOption(final String shortName,
            final String longName, final String argumentName,
            final String description) {
        return Option.builder(shortName).longOpt(longName).hasArg()
                .argName(argumentName).required().desc(description).get();
    }

    private static Path parseDirectory(final String value)
            throws ParseException {
        final Path directory;
        try {
            directory = Path.of(value).normalize();
        } catch (RuntimeException exception) {
            throw new ParseException("Invalid directory: " + value);
        }
        if (!directory.isAbsolute()) {
            throw new ParseException("Directory must be absolute: " + value);
        }
        return directory;
    }

    private static int parsePositiveInt(final String value,
            final String optionName, final int maximum) throws ParseException {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ParseException(
                    "Option --" + optionName + " must be an integer: " + value);
        }
        if (parsed < 1 || parsed > maximum) {
            throw new ParseException("Option --" + optionName
                    + " must be between 1 and " + maximum + ": " + value);
        }
        return parsed;
    }
}
