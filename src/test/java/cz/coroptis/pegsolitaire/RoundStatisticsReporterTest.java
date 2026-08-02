package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundStatisticsReporterTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void printsSortedCompletedRoundsAndTotal() throws Exception {
        createRound(4, List.of());
        createRound(1, List.of(EnglishBoard.INITIAL_STATE));
        createRound(2, List.of(10L, 20L, 30L));
        Files.createDirectories(temporaryDirectory.resolve("3.in-progress"));
        Files.createDirectories(temporaryDirectory.resolve("notes"));
        Files.writeString(temporaryDirectory.resolve("5"), "not an index");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (PrintStream output = new PrintStream(bytes, true,
                StandardCharsets.UTF_8)) {
            new RoundStatisticsReporter(temporaryDirectory).print(output);
        }

        final String expected = row("State No.", "Boards")
                + row("1", "1")
                + row("2", "3")
                + row("4", "0")
                + "---------------------------------------\n"
                + row("Total", "4");
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void emptyRootPrintsZeroTotal() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (PrintStream output = new PrintStream(bytes, true,
                StandardCharsets.UTF_8)) {
            new RoundStatisticsReporter(temporaryDirectory).print(output);
        }

        final String expected = row("State No.", "Boards")
                + "---------------------------------------\n"
                + row("Total", "0");
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8));
    }

    private void createRound(final int round, final List<Long> states)
            throws Exception {
        final Path roundDirectory = temporaryDirectory
                .resolve(Integer.toString(round));
        Files.createDirectory(roundDirectory);
        try (SegmentIndex<Long, NullValue> index = new HestiaRoundStore()
                .create(roundDirectory)) {
            states.forEach(state -> index.put(state, NULL));
            index.maintenance().compactAndWait();
        }
    }

    private String row(final String firstColumn, final String secondColumn) {
        return String.format("%-18s %20s%n", firstColumn, secondColumn);
    }
}
