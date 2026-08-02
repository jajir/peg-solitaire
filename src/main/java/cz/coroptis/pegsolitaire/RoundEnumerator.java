package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;

/**
 * Initializes the search and advances one persisted breadth-first round.
 */
public final class RoundEnumerator {

    private final EnglishBoard board;
    private final BoardSymmetry symmetry;
    private final HestiaRoundStore store;
    private final RoundDirectories directories;

    /**
     * Creates an enumerator for one persistent data root.
     *
     * @param dataRoot persistent round root
     */
    public RoundEnumerator(final Path dataRoot) {
        board = new EnglishBoard();
        symmetry = new BoardSymmetry(board);
        store = new HestiaRoundStore();
        directories = new RoundDirectories(dataRoot);
    }

    /**
     * Initializes round one or advances the latest completed round once.
     *
     * @return invocation statistics
     * @throws IOException when directory management fails
     */
    public RoundResult runOneRound() throws IOException {
        directories.ensureRootExists();
        final OptionalInt latestRound = directories.latestCompletedRound();
        if (latestRound.isEmpty()) {
            return initialize();
        }
        return advance(latestRound.getAsInt());
    }

    private RoundResult initialize() throws IOException {
        final int round = 1;
        directories.deleteInProgress(round);
        final Path temporary = directories.inProgress(round);
        Files.createDirectory(temporary);
        try (SegmentIndex<Long, NullValue> index = store.create(temporary)) {
            final long initial = symmetry.canonicalize(EnglishBoard.INITIAL_STATE);
            index.put(initial, NULL);
            index.maintenance().compactAndWait();
        }
        directories.publish(round);
        return RoundResult.initialized();
    }

    private RoundResult advance(final int sourceRound) throws IOException {
        final Path sourcePath = directories.completed(sourceRound);
        try (SegmentIndex<Long, NullValue> source = store.open(sourcePath);
                Stream<Entry<Long, NullValue>> entries = source.getStream()) {
            final Iterator<Entry<Long, NullValue>> iterator = entries.iterator();
            if (!iterator.hasNext()) {
                return RoundResult.terminal(sourceRound);
            }
            return generateRound(sourceRound, iterator);
        }
    }

    private RoundResult generateRound(final int sourceRound,
            final Iterator<Entry<Long, NullValue>> iterator) throws IOException {
        if (sourceRound == Integer.MAX_VALUE) {
            throw new IOException("Round number overflow");
        }
        final int destinationRound = sourceRound + 1;
        directories.deleteInProgress(destinationRound);
        final Path temporary = directories.inProgress(destinationRound);
        Files.createDirectory(temporary);

        long processedStates = 0L;
        long generatedMoves = 0L;
        long uniqueStates;
        try (SegmentIndex<Long, NullValue> destination = store.create(temporary)) {
            do {
                final long state = iterator.next().getKey();
                generatedMoves += board.generateSuccessors(state,
                        successor -> destination.put(
                                symmetry.canonicalize(successor), NULL));
                processedStates++;
            } while (iterator.hasNext());
            destination.maintenance().compactAndWait();
            try (Stream<Entry<Long, NullValue>> output = destination.getStream()) {
                uniqueStates = output.count();
            }
        }
        directories.publish(destinationRound);
        return RoundResult.counted(sourceRound, processedStates,
                generatedMoves, uniqueStates);
    }
}
