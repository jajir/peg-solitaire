package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.chunkentryfile.KeyPageCodec;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;
import org.hestiastore.index.senku.SenkuLongKeySummary;

/**
 * Initializes the search and advances one persisted breadth-first round.
 */
public final class RoundEnumerator {

    private static final int DEFAULT_WORKER_COUNT = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 32;

    private final PegSolitaireBoard board;
    private final BoardSymmetry symmetry;
    private final HestiaRoundStore store;
    private final BoardStateEncoding encoding;
    private final RoundDirectories directories;
    private final int workerCount;
    private final int queueCapacity;
    private final boolean verifyReadySummary;

    /**
     * Creates an enumerator for one persistent data root.
     *
     * @param dataRoot persistent round root
     */
    public RoundEnumerator(final Path dataRoot) {
        this(dataRoot, BoardVariant.ENGLISH, DEFAULT_WORKER_COUNT,
                DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates an enumerator with explicit parallel processing limits.
     *
     * @param dataRoot      persistent round root
     * @param workerCount   number of board-processing workers
     * @param queueCapacity maximum queued batches of board states
     */
    public RoundEnumerator(final Path dataRoot, final int workerCount,
            final int queueCapacity) {
        this(dataRoot, BoardVariant.ENGLISH, workerCount, queueCapacity);
    }

    /**
     * Creates an enumerator for an explicit board and parallel processing
     * limits.
     *
     * @param dataRoot      persistent round root
     * @param boardVariant  board implementation
     * @param workerCount   number of board-processing workers
     * @param queueCapacity maximum queued batches of board states
     */
    public RoundEnumerator(final Path dataRoot, final BoardVariant boardVariant,
            final int workerCount, final int queueCapacity) {
        this(dataRoot, boardVariant, workerCount, queueCapacity, false);
    }

    /**
     * Creates an enumerator with optional expensive full-stream verification.
     *
     * @param dataRoot           persistent round root
     * @param boardVariant       board implementation
     * @param workerCount        board-processing workers
     * @param queueCapacity      pending input batches
     * @param verifyReadySummary scan every finalized output to verify count and
     *                           order
     */
    public RoundEnumerator(final Path dataRoot, final BoardVariant boardVariant,
            final int workerCount, final int queueCapacity,
            final boolean verifyReadySummary) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "queueCapacity must be positive");
        }
        if (boardVariant == null) {
            throw new IllegalArgumentException("boardVariant must not be null");
        }
        board = boardVariant.createBoard();
        symmetry = new BoardSymmetry(board);
        store = new HestiaRoundStore(board.holeCount());
        encoding = new BoardStateEncoding(board);
        directories = new RoundDirectories(dataRoot);
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
        this.verifyReadySummary = verifyReadySummary;
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
        final long initial = symmetry.canonicalize(board.initialState());
        final SortedStateSampler sampler = new SortedStateSampler(
                board.holeCount());
        sampler.add(initial);
        final SenkuWriting<Long, NullValue> writing = store.create(temporary,
                new RangeShardRouter(new long[0]),
                encoding.codecForPopulation(Long.bitCount(initial)));
        writing.put(initial, NULL);
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting()) {
            if (verifyReadySummary) {
                verifyReady(ready);
            }
        }
        RoundStateSampleFile.write(directories.stateSampleFile(round),
                sampler.snapshot());
        directories.publish(round);
        return RoundResult.initialized();
    }

    private RoundResult advance(final int sourceRound) throws IOException {
        final Path sourcePath = directories.completed(sourceRound);
        try (SenkuReady<Long, NullValue> source = store.open(sourcePath)) {
            final Optional<RoundStateSample> persisted = RoundStateSampleFile
                    .read(directories.stateSampleFile(sourceRound),
                            board.holeCount());
            // An index written before sampling was introduced needs one extra
            // read-only pass. All subsequent rounds persist their own sample.
            final RoundStateSample sample = persisted.isPresent()
                    ? persisted.get()
                    : distribution(source);
            if (sample.stateCount() != source.recordCount()) {
                throw new IOException(
                        "Round sample count does not match ready manifests");
            }
            final RangeShardRouter router = RangeShardRouter
                    .fromSourceSample(sample, board, symmetry);
            try (Stream<Entry<Long, NullValue>> entries = source.openStream()) {
                final Iterator<Entry<Long, NullValue>> iterator = entries
                        .iterator();
                if (!iterator.hasNext()) {
                    return RoundResult.terminal(sourceRound);
                }
                return generateRound(sourceRound, iterator, router,
                        encoding.codecForSuccessors(sample));
            }
        }
    }

    private RoundResult generateRound(final int sourceRound,
            final Iterator<Entry<Long, NullValue>> iterator,
            final RangeShardRouter router, final KeyPageCodec<Long> codec)
            throws IOException {
        if (sourceRound == Integer.MAX_VALUE) {
            throw new IOException("Round number overflow");
        }
        final int destinationRound = sourceRound + 1;
        directories.deleteInProgress(destinationRound);
        final Path temporary = directories.inProgress(destinationRound);
        Files.createDirectory(temporary);

        final ParallelRoundProcessor.ProcessingResult processingResult;
        final RoundStateSample sample;
        final SenkuWriting<Long, NullValue> destination = store
                .create(temporary, router, codec);
        processingResult = new ParallelRoundProcessor(board, symmetry,
                workerCount, queueCapacity).process(iterator, destination);
        try (SenkuReady<Long, NullValue> ready = destination.finishWriting()) {
            sample = distribution(ready);
            if (verifyReadySummary) {
                verifyReady(ready);
            }
        }
        RoundStateSampleFile
                .write(directories.stateSampleFile(destinationRound), sample);
        directories.publish(destinationRound);
        return RoundResult.counted(sourceRound,
                processingResult.processedStates(),
                processingResult.generatedMoves(), sample.stateCount());
    }

    private RoundStateSample sample(final SenkuReady<Long, NullValue> ready) {
        final SortedStateSampler sampler = new SortedStateSampler(
                board.holeCount());
        try (Stream<Entry<Long, NullValue>> output = ready.openStream()) {
            output.forEach(entry -> sampler.add(entry.getKey()));
        }
        return sampler.snapshot();
    }

    private RoundStateSample distribution(
            final SenkuReady<Long, NullValue> ready) {
        final Optional<SenkuLongKeySummary> summary = ready.longKeySummary();
        if (summary.isPresent()) {
            final SenkuLongKeySummary weighted = summary.orElseThrow();
            if (weighted.recordCount() != ready.recordCount()) {
                throw new IllegalStateException(
                        "Ready summary has inconsistent record count");
            }
            return RoundStateSample.fromWeighted(board.holeCount(),
                    ready.recordCount(), weighted.keys(), weighted.weights());
        }
        final RoundStateSample scanned = sample(ready);
        if (scanned.stateCount() != ready.recordCount()) {
            throw new IllegalStateException(
                    "Scanned ready count differs from terminal manifests");
        }
        return scanned;
    }

    private void verifyReady(final SenkuReady<Long, NullValue> ready) {
        if (sample(ready).stateCount() != ready.recordCount()) {
            throw new IllegalStateException(
                    "Full ready verification found an inconsistent count");
        }
    }
}
