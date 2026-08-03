package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;

class PegSolitaireCliTest {

    @Test
    void parsesCountConfiguration() throws Exception {
        final CliConfiguration configuration = PegSolitaireCli.parse(new String[] {
                "count", "--board", "english", "--directory", "/tmp/rounds",
                "--workers", "8", "--queue-capacity", "32"
        });

        assertEquals(CliConfiguration.Action.COUNT, configuration.action());
        assertEquals(BoardVariant.ENGLISH, configuration.board());
        assertEquals(Path.of("/tmp/rounds"), configuration.directory());
        assertEquals(8, configuration.workerCount());
        assertEquals(32, configuration.queueCapacity());
    }

    @Test
    void parsesStatsConfiguration() throws Exception {
        final CliConfiguration configuration = PegSolitaireCli.parse(new String[] {
                "stats", "-b", "english", "-d", "/tmp/rounds"
        });

        assertEquals(CliConfiguration.Action.STATS, configuration.action());
        assertEquals(BoardVariant.ENGLISH, configuration.board());
        assertEquals(Path.of("/tmp/rounds"), configuration.directory());
    }

    @Test
    void rejectsCountOptionsForStats() {
        assertThrows(ParseException.class, () -> PegSolitaireCli.parse(new String[] {
                "stats", "--board", "english", "--directory", "/tmp/rounds",
                "--workers", "8"
        }));
    }

    @Test
    void parsesAdditionalBoardsAndRejectsUnknownBoardAndCommand()
            throws Exception {
        final CliConfiguration european = PegSolitaireCli.parse(new String[] {
                "count", "--board", "european", "--directory", "/tmp/rounds",
                "--workers", "8", "--queue-capacity", "32"
        });
        assertEquals(BoardVariant.EUROPEAN, european.board());

        final CliConfiguration senku = PegSolitaireCli.parse(new String[] {
                "stats", "--board", "senku", "--directory", "/tmp/rounds"
        });
        assertEquals(BoardVariant.SENKU, senku.board());

        assertThrows(ParseException.class, () -> PegSolitaireCli.parse(new String[] {
                "count", "--board", "german", "--directory", "/tmp/rounds",
                "--workers", "8", "--queue-capacity", "32"
        }));
        assertThrows(ParseException.class,
                () -> PegSolitaireCli.parse(new String[] { "solve" }));
    }

    @Test
    void rejectsRelativeDirectoryAndInvalidParallelLimits() {
        assertThrows(ParseException.class, () -> PegSolitaireCli.parse(new String[] {
                "count", "--board", "english", "--directory", "rounds",
                "--workers", "8", "--queue-capacity", "32"
        }));
        assertThrows(ParseException.class, () -> PegSolitaireCli.parse(new String[] {
                "count", "--board", "english", "--directory", "/tmp/rounds",
                "--workers", "0", "--queue-capacity", "32"
        }));
    }

    @Test
    void printsBothCommandsInHelp() {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintWriter output = new PrintWriter(bytes, true,
                StandardCharsets.UTF_8);

        PegSolitaireCli.printHelp(output);

        final String help = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("peg-solitaire count"));
        assertTrue(help.contains("peg-solitaire stats"));
        assertTrue(help.contains("--queue-capacity"));
    }
}
