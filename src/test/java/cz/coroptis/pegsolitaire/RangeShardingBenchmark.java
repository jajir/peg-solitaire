package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.chunkentryfile.KeyPageCodecs;
import org.hestiastore.index.chunkentryfile.LongKeyPageReader;
import org.hestiastore.index.chunkentryfile.SingleChunkEntryWriterImpl;
import org.hestiastore.index.chunkstore.ChunkData;
import org.hestiastore.index.chunkstore.ChunkFilterZstdCompress;
import org.hestiastore.index.chunkstore.ChunkFilterZstdDecompress;
import org.hestiastore.index.chunkstore.ChunkHeader;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.datatype.TypeDescriptorLong;
import org.hestiastore.index.datatype.TypeDescriptorNull;
import org.hestiastore.index.directory.MemFileReader;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;

/**
 * Opt-in, deterministic Senku routing benchmark, not a unit test. See
 * doc/range-sharding-results.md for commands and measurement boundaries.
 */
public final class RangeShardingBenchmark {

    private static final int PAGE_KEYS = 1_000_000;
    private static final int MAX_ENCODED_PAGE_BYTES = PAGE_KEYS * 10;
    private static final PegSolitaireBoard BOARD = new SenkuBoard();
    private static final BoardSymmetry SYMMETRY = new BoardSymmetry(BOARD);

    private RangeShardingBenchmark() {
    }

    /**
     * Generates a corpus, compares payloads, or times real Senku round writes.
     */
    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "generate <corpus> <last-round>; "
                            + "density <corpus> <round>; run <corpus> <round> <output-parent> <pairs>");
        }
        final Path corpus = Path.of(args[1]);
        final int round = Integer.parseInt(args[2]);
        if (round < 2 || round > 12) {
            throw new IllegalArgumentException(
                    "Benchmark rounds must be between 2 and 12");
        }
        if ("generate".equals(args[0])) {
            generate(corpus, round);
            return;
        }
        final long[] source = read(
                corpus.resolve("senku-round-" + (round - 1) + ".longs"));
        final long[] target = read(
                corpus.resolve("senku-round-" + round + ".longs"));
        final SortedStateSampler sampler = new SortedStateSampler(
                BOARD.holeCount());
        for (final long key : source) {
            sampler.add(key);
        }
        final RoundStateSample sample = sampler.snapshot();
        if ("density".equals(args[0])) {
            final long start = System.nanoTime();
            final RangeShardRouter router = RangeShardRouter
                    .fromSourceSample(sample, BOARD, SYMMETRY);
            System.out.printf(
                    "forecast round=%d source=%d samples=%d stride=%d ms=%.3f%n",
                    round, source.length, sample.states().length,
                    sample.stride(), elapsed(start));
            density(round, "prefix", target,
                    new HestiaRoundStore(49)::shardHash);
            density(round, "forecast", target, router);
        } else if ("run".equals(args[0]) && args.length == 5) {
            final Path outputParent = Path.of(args[3]);
            Files.createDirectories(outputParent);
            final int pairs = Integer.parseInt(args[4]);
            if (pairs < 1 || pairs > 10) {
                throw new IllegalArgumentException(
                        "Use between 1 and 10 benchmark pairs");
            }
            for (int pair = 1; pair <= pairs; pair++) {
                // Alternate order to reduce systematic JIT/cache/order bias.
                run(round, pair, pair % 2 == 0, source, target, sample,
                        outputParent);
                run(round, pair, pair % 2 != 0, source, target, sample,
                        outputParent);
            }
        } else {
            throw new IllegalArgumentException("Invalid benchmark arguments");
        }
    }

    private static void run(final int round, final int pair,
            final boolean forecast, final long[] source, final long[] expected,
            final RoundStateSample sample, final Path outputParent)
            throws IOException {
        final String mode = forecast ? "forecast" : "prefix";
        final Path directory = Files.createTempDirectory(outputParent,
                "round-" + round + "-" + mode + "-" + pair + "-");
        final HestiaRoundStore store = new HestiaRoundStore(BOARD.holeCount());
        final long start = System.nanoTime();
        final RangeShardRouter router = forecast
                ? RangeShardRouter.fromSourceSample(sample, BOARD, SYMMETRY)
                : null;
        final double forecastMs = elapsed(start);
        final SenkuWriting<Long, NullValue> writing = forecast
                ? store.create(directory, router)
                : store.create(directory);
        final ParallelRoundProcessor.ProcessingResult processed;
        try (Stream<Entry<Long, NullValue>> entries = Arrays.stream(source)
                .mapToObj(key -> Entry.of(key, NULL))) {
            processed = new ParallelRoundProcessor(BOARD, SYMMETRY, 8, 32)
                    .process(entries.iterator(), writing);
        }
        final double ingestMs = elapsed(start);
        final SortedStateSampler destinationSample = new SortedStateSampler(
                BOARD.holeCount());
        final double finishMs;
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting()) {
            finishMs = elapsed(start);
            try (Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
                final Iterator<Entry<Long, NullValue>> iterator = entries
                        .iterator();
                int index = 0;
                while (iterator.hasNext()) {
                    final long key = iterator.next().getKey();
                    if (index >= expected.length || key != expected[index++]) {
                        throw new IllegalStateException(
                                "Persisted frontier differs from exact corpus");
                    }
                    destinationSample.add(key);
                }
                if (index != expected.length) {
                    throw new IllegalStateException(
                            "Persisted frontier is truncated");
                }
            }
        }
        long sidecarBytes = 0;
        if (forecast) {
            final Path sidecar = directory
                    .resolveSibling(directory.getFileName() + ".state-sample");
            RoundStateSampleFile.write(sidecar, destinationSample.snapshot());
            sidecarBytes = Files.size(sidecar);
        }
        final double totalMs = elapsed(start);
        long indexBytes = 0;
        long partBytes = 0;
        long fileCount = 0;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                    .toList()) {
                final long size = Files.size(file);
                indexBytes += size;
                if (file.getFileName().toString().endsWith(".chunk")) {
                    partBytes += size;
                }
                fileCount++;
            }
        }
        System.out.printf(
                "run round=%d pair=%d mode=%s source=%d moves=%d states=%d "
                        + "forecast_ms=%.3f ingest_ms=%.3f finish_ms=%.3f total_ms=%.3f "
                        + "index_bytes=%d part_bytes=%d sidecar_bytes=%d total_bytes=%d "
                        + "bytes_per_state=%.6f files=%d verified=true directory=%s%n",
                round, pair, mode, processed.processedStates(),
                processed.generatedMoves(), expected.length, forecastMs,
                ingestMs, finishMs, totalMs, indexBytes, partBytes,
                sidecarBytes, indexBytes + sidecarBytes,
                (indexBytes + sidecarBytes) / (double) expected.length,
                fileCount, directory);
    }

    private static void density(final int round, final String mode,
            final long[] keys, final ToIntFunction<Long> hash) {
        final int[] counts = new int[RangeShardRouter.SHARD_COUNT];
        for (final long key : keys) {
            counts[Math.floorMod(hash.applyAsInt(key), counts.length)]++;
        }
        final long[][] shards = new long[counts.length][];
        for (int shard = 0; shard < counts.length; shard++) {
            shards[shard] = new long[counts[shard]];
        }
        final int[] offsets = new int[counts.length];
        for (final long key : keys) {
            final int shard = Math.floorMod(hash.applyAsInt(key),
                    counts.length);
            shards[shard][offsets[shard]++] = key;
        }
        long rawBytes = 0;
        long compressedBytes = 0;
        int pages = 0;
        for (final long[] shard : shards) {
            for (int start = 0; start < shard.length; start += PAGE_KEYS) {
                final int end = Math.min(shard.length, start + PAGE_KEYS);
                final SingleChunkEntryWriterImpl<Long, NullValue> writer = new SingleChunkEntryWriterImpl<>(
                        new TypeDescriptorLong(), new TypeDescriptorNull(),
                        MAX_ENCODED_PAGE_BYTES,
                        KeyPageCodecs.longDeltaVarint());
                for (int index = start; index < end; index++) {
                    writer.putLongKey(shard[index], NULL);
                }
                final var payload = writer.closeSequence();
                rawBytes += payload.length();
                final ChunkData compressed = new ChunkFilterZstdCompress(3)
                        .apply(ChunkData.ofSequence(0, 0,
                                ChunkHeader.MAGIC_NUMBER, 3, payload));
                compressedBytes += compressed.getPayloadSequence().length();
                final ChunkData decoded = new ChunkFilterZstdDecompress()
                        .apply(compressed);
                final LongKeyPageReader decoder = new LongKeyPageReader(
                        KeyPageCodecs.longDeltaVarint());
                try (MemFileReader input = new MemFileReader(
                        decoded.getPayloadSequence().toByteArray())) {
                    for (int index = start; index < end; index++) {
                        final Long key = decoder.read(input);
                        if (key == null || key.longValue() != shard[index]) {
                            throw new IllegalStateException(
                                    "Compressed key page failed round trip");
                        }
                    }
                    if (decoder.read(input) != null) {
                        throw new IllegalStateException(
                                "Unexpected trailing key");
                    }
                }
                pages++;
            }
        }
        System.out.printf(
                "density round=%d mode=%s states=%d raw_bytes=%d payload_bytes=%d "
                        + "bytes_per_state=%.6f states_per_byte=%.6f max_mean=%.3f empty=%d pages=%d verified=true%n",
                round, mode, keys.length, rawBytes, compressedBytes,
                compressedBytes / (double) keys.length,
                keys.length / (double) compressedBytes,
                Arrays.stream(counts).max().orElse(0) * counts.length
                        / (double) keys.length,
                Arrays.stream(counts).filter(count -> count == 0).count(),
                pages);
    }

    private static long[] read(final Path file) throws IOException {
        final long size = Files.size(file);
        if (size % Long.BYTES != 0 || size / Long.BYTES > Integer.MAX_VALUE) {
            throw new IOException(
                    "Invalid or oversized frontier corpus: " + file);
        }
        final long[] keys = new long[(int) (size / Long.BYTES)];
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            for (int index = 0; index < keys.length; index++) {
                keys[index] = input.readLong();
                if (index > 0 && keys[index] <= keys[index - 1]) {
                    throw new IOException(
                            "Corpus is not sorted and unique: " + file);
                }
            }
        }
        return keys;
    }

    private static void generate(final Path directory, final int lastRound)
            throws IOException {
        Files.createDirectories(directory);
        long[] source = { SYMMETRY.canonicalize(BOARD.initialState()) };
        for (int round = 1; round <= lastRound; round++) {
            final Path file = directory
                    .resolve("senku-round-" + round + ".longs");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(file,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)))) {
                for (final long key : source) {
                    output.writeLong(key);
                }
            }
            System.out.printf("corpus round=%d states=%d file=%s%n", round,
                    source.length, file);
            if (round == lastRound) {
                return;
            }
            final StateBuffer successors = new StateBuffer();
            final long[] transformed = new long[BoardSymmetry.TRANSFORM_COUNT];
            for (final long key : source) {
                SYMMETRY.transformAll(key, transformed);
                BOARD.generateSuccessors(key, next -> successors.add(
                        SYMMETRY.canonicalizeMove(transformed, key ^ next)));
            }
            source = successors.unique();
        }
    }

    private static double elapsed(final long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static final class StateBuffer {
        private long[] keys = new long[1024];
        private int size;

        void add(final long key) {
            if (size == keys.length) {
                keys = Arrays.copyOf(keys, Math.multiplyExact(keys.length, 2));
            }
            keys[size++] = key;
        }

        long[] unique() {
            Arrays.sort(keys, 0, size);
            int count = 0;
            for (int index = 0; index < size; index++) {
                if (count == 0 || keys[index] != keys[count - 1]) {
                    keys[count++] = keys[index];
                }
            }
            return Arrays.copyOf(keys, count);
        }
    }
}
