package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundEnumeratorTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void initializesThenAdvancesWithoutChangingCompletedRound() throws Exception {
        final RoundEnumerator enumerator = new RoundEnumerator(temporaryDirectory);

        final RoundResult initialized = enumerator.runOneRound();
        assertTrue(initialized.isInitialized());
        assertEquals(List.of(EnglishBoard.INITIAL_STATE), keys(1));

        final RoundResult counted = enumerator.runOneRound();
        assertEquals(1, counted.sourceRound());
        assertEquals(2, counted.destinationRound());
        assertEquals(1L, counted.processedStates());
        assertEquals(4L, counted.generatedMoves());
        assertEquals(1L, counted.uniqueStates());
        assertEquals(List.of(EnglishBoard.INITIAL_STATE), keys(1));
        assertEquals(1, keys(2).size());
    }

    @Test
    void rebuildsStaleInitializationDirectory() throws Exception {
        final Path stale = temporaryDirectory.resolve("1.in-progress");
        Files.createDirectories(stale.resolve("nested"));
        Files.writeString(stale.resolve("nested/partial"), "partial");

        new RoundEnumerator(temporaryDirectory).runOneRound();

        assertFalse(Files.exists(stale));
        assertEquals(List.of(EnglishBoard.INITIAL_STATE), keys(1));
    }

    @Test
    void rebuildsStaleNextRoundDirectory() throws Exception {
        final RoundEnumerator enumerator = new RoundEnumerator(temporaryDirectory);
        enumerator.runOneRound();
        final Path stale = temporaryDirectory.resolve("2.in-progress");
        Files.createDirectories(stale.resolve("nested"));
        Files.writeString(stale.resolve("nested/partial"), "partial");

        final RoundResult result = enumerator.runOneRound();

        assertEquals(2, result.destinationRound());
        assertFalse(Files.exists(stale));
        assertEquals(1, keys(2).size());
    }

    @Test
    void emptyLatestRoundStopsWithoutCreatingAnotherRound() throws Exception {
        createEmptyRound(4);
        Files.createDirectories(temporaryDirectory.resolve("5.in-progress"));
        Files.createDirectories(temporaryDirectory.resolve("unrelated"));

        final RoundResult result = new RoundEnumerator(temporaryDirectory)
                .runOneRound();

        assertTrue(result.isTerminal());
        assertEquals(4, result.sourceRound());
        assertFalse(Files.exists(temporaryDirectory.resolve("5")));
        assertTrue(Files.exists(temporaryDirectory.resolve("5.in-progress")));
    }

    @Test
    void nonEmptyTerminalFrontierPublishesOneEmptyRound() throws Exception {
        createRound(1, List.of(1L));
        final RoundEnumerator enumerator = new RoundEnumerator(temporaryDirectory);

        final RoundResult counted = enumerator.runOneRound();
        final RoundResult terminal = enumerator.runOneRound();

        assertEquals(0L, counted.generatedMoves());
        assertEquals(0L, counted.uniqueStates());
        assertTrue(Files.isDirectory(temporaryDirectory.resolve("2")));
        assertTrue(terminal.isTerminal());
        assertFalse(Files.exists(temporaryDirectory.resolve("3")));
    }

    @Test
    void generatedKeysAreCanonical() throws Exception {
        final RoundEnumerator enumerator = new RoundEnumerator(temporaryDirectory);
        enumerator.runOneRound();
        enumerator.runOneRound();
        enumerator.runOneRound();
        final BoardSymmetry symmetry = new BoardSymmetry(new EnglishBoard());

        for (long state : keys(3)) {
            assertEquals(state, symmetry.canonicalize(state));
        }
        assertEquals(2, keys(3).size());
    }

    @Test
    void parallelWorkersProduceExpectedPersistedFrontiers() throws Exception {
        final RoundEnumerator enumerator = new RoundEnumerator(temporaryDirectory);
        final long[] expectedUniqueStates = { 1L, 1L, 2L, 8L, 39L, 171L,
                719L };

        final RoundResult initialized = enumerator.runOneRound();
        assertEquals(expectedUniqueStates[0], initialized.uniqueStates());
        for (int round = 2; round <= expectedUniqueStates.length; round++) {
            final RoundResult counted = enumerator.runOneRound();
            assertEquals(round, counted.destinationRound());
            assertEquals(expectedUniqueStates[round - 1],
                    counted.uniqueStates());
        }

        assertEquals(719, keys(7).size());
    }

    private void createEmptyRound(final int round) throws Exception {
        createRound(round, List.of());
    }

    private void createRound(final int round, final List<Long> states)
            throws Exception {
        final Path path = temporaryDirectory.resolve(Integer.toString(round));
        Files.createDirectory(path);
        try (SegmentIndex<Long, NullValue> index = new HestiaRoundStore()
                .create(path)) {
            states.forEach(state -> index.put(state, NULL));
            index.maintenance().compactAndWait();
        }
    }

    private List<Long> keys(final int round) throws Exception {
        try (SegmentIndex<Long, NullValue> index = new HestiaRoundStore()
                .open(temporaryDirectory.resolve(Integer.toString(round)));
                Stream<Entry<Long, NullValue>> entries = index.getStream()) {
            return entries.map(Entry::getKey).sorted().toList();
        }
    }
}
