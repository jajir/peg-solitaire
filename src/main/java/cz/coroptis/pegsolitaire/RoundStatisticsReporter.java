package cz.coroptis.pegsolitaire;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;

/**
 * Scans completed round indexes and prints exact board counts.
 */
public final class RoundStatisticsReporter {

    private static final String ROW_FORMAT = "%-18s %20s%n";
    private static final String SEPARATOR = "---------------------------------------";

    private final RoundDirectories directories;
    private final HestiaRoundStore store;

    /**
     * Creates a reporter for one persistent round root.
     *
     * @param dataRoot completed-round root
     */
    public RoundStatisticsReporter(final Path dataRoot) {
        directories = new RoundDirectories(dataRoot);
        store = new HestiaRoundStore();
    }

    /**
     * Prints a two-column table containing each completed round and its exact
     * number of board states, followed by the total.
     *
     * @param output report destination
     * @throws IOException when round discovery fails
     */
    public void print(final PrintStream output) throws IOException {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        final List<Integer> rounds = directories.completedRounds();
        long total = 0L;
        output.printf(ROW_FORMAT, "State No.", "Boards");
        for (int round : rounds) {
            final long boards = countBoards(round);
            total = Math.addExact(total, boards);
            output.printf(ROW_FORMAT, Integer.toString(round),
                    Long.toString(boards));
            output.flush();
        }
        output.println(SEPARATOR);
        output.printf(ROW_FORMAT, "Total", Long.toString(total));
    }

    private long countBoards(final int round) {
        try (SegmentIndex<Long, NullValue> index = store
                .open(directories.completed(round));
                Stream<Entry<Long, NullValue>> entries = index.getStream()) {
            return entries.count();
        }
    }
}
