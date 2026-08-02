package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PegSolitaireMainTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void unifiedMainCountsAndReportsStatistics() throws Exception {
        final CapturedStreams countStreams = new CapturedStreams();
        final String directory = temporaryDirectory.toString();

        final int countExitCode = PegSolitaireMain.run(new String[] {
                "count", "--board", "english", "--directory", directory,
                "--workers", "2", "--queue-capacity", "4"
        }, countStreams.output(), countStreams.error());

        assertEquals(0, countExitCode);
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("1")));
        assertTrue(countStreams.outputText().contains("Initialized round 1"));
        assertEquals("", countStreams.errorText());

        final CapturedStreams statsStreams = new CapturedStreams();
        final int statsExitCode = PegSolitaireMain.run(new String[] {
                "stats", "--board", "english", "--directory", directory
        }, statsStreams.output(), statsStreams.error());

        assertEquals(0, statsExitCode);
        assertTrue(statsStreams.outputText().contains("State No."));
        assertTrue(statsStreams.outputText().contains("Total"));
        assertTrue(statsStreams.outputText().contains("1"));
        assertEquals("", statsStreams.errorText());
    }

    @Test
    void invalidCommandFailsBeforeCreatingDirectory() {
        final Path missing = temporaryDirectory.resolve("must-not-be-created");
        final CapturedStreams streams = new CapturedStreams();

        final int exitCode = PegSolitaireMain.run(new String[] {
                "count", "--board", "english", "--directory",
                missing.toString(), "--workers", "0", "--queue-capacity", "4"
        }, streams.output(), streams.error());

        assertEquals(2, exitCode);
        assertFalse(Files.exists(missing));
        assertTrue(streams.errorText().contains("Command-line error"));
    }

    @Test
    void helpReturnsSuccessWithoutCreatingData() {
        final CapturedStreams streams = new CapturedStreams();

        final int exitCode = PegSolitaireMain.run(new String[] { "--help" },
                streams.output(), streams.error());

        assertEquals(0, exitCode);
        assertTrue(streams.outputText().contains("peg-solitaire count"));
        assertEquals("", streams.errorText());
    }

    private static final class CapturedStreams {

        private final ByteArrayOutputStream outputBytes =
                new ByteArrayOutputStream();
        private final ByteArrayOutputStream errorBytes =
                new ByteArrayOutputStream();
        private final PrintStream output = new PrintStream(outputBytes, true,
                StandardCharsets.UTF_8);
        private final PrintStream error = new PrintStream(errorBytes, true,
                StandardCharsets.UTF_8);

        private PrintStream output() {
            return output;
        }

        private PrintStream error() {
            return error;
        }

        private String outputText() {
            return outputBytes.toString(StandardCharsets.UTF_8);
        }

        private String errorText() {
            return errorBytes.toString(StandardCharsets.UTF_8);
        }
    }
}
