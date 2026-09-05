package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.chunkentryfile.KeyPageCodec;
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
 * Opt-in exact-frontier benchmark. The coordinator starts a separate JVM with
 * exactly one production jar, so the baseline runs its actual old processor,
 * kernel and engine without per-key reflection or mixed native libraries.
 * Baseline execution must only call APIs available in the preserved old jar.
 * See doc/round-optimization-results.md for commands and timing boundaries.
 */
public final class RoundOptimizationBenchmark {

    private static final List<String> MODES = List.of("baseline", "fast-only",
            "fast-cache", "full");
    private static final int PAGE_KEYS = 1_000_000;

    private RoundOptimizationBenchmark() {
    }

    /**
     * Runs isolated timed trials or compares exact compressed page payloads.
     */
    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length < 3) {
            throw usage();
        }
        final Path corpus = Path.of(args[1]).toAbsolutePath();
        final int round = Integer.parseInt(args[2]);
        if (round < 2 || round > 12) {
            throw new IllegalArgumentException("Use destination round 2 to 12");
        }
        if ("run".equals(args[0]) && (args.length == 6 || args.length == 7)) {
            coordinate(corpus, round, Path.of(args[3]).toAbsolutePath(),
                    Path.of(args[4]).toAbsolutePath(),
                    Integer.parseInt(args[5]),
                    args.length == 7 ? List.of(args[6].split(",")) : MODES);
            return;
        }
        final long[] source = read(
                corpus.resolve("senku-round-" + (round - 1) + ".longs"),
                50 - round);
        final long[] expected = read(
                corpus.resolve("senku-round-" + round + ".longs"), 49 - round);
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final SortedStateSampler sampler = new SortedStateSampler(49);
        for (final long key : source) {
            sampler.add(key);
        }
        final RoundStateSample sample = sampler.snapshot();
        if ("child".equals(args[0]) && args.length == 7) {
            final String mode = args[3];
            validateModes(List.of(mode));
            final Path output = Path.of(args[4]);
            final int trial = Integer.parseInt(args[5]);
            final Path requestedJar = Path.of(args[6]).toRealPath();
            final Path actualJar = origin(HestiaRoundStore.class).toRealPath();
            if (!actualJar.equals(requestedJar)) {
                throw new IllegalStateException(
                        "Wrong production classes: " + actualJar);
            }
            System.out.printf("environment mode=%s trial=%d java=%s "
                    + "vm=%s os=%s arch=%s cpus=%d max_heap=%d "
                    + "production_jar=%s source_file=%s target_file=%s%n", mode,
                    trial, System.getProperty("java.version"),
                    System.getProperty("java.vm.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().maxMemory(), actualJar,
                    corpus.resolve("senku-round-" + (round - 1) + ".longs"),
                    corpus.resolve("senku-round-" + round + ".longs"));
            runTrial(round, trial, "warmup", mode, source, expected, sample,
                    board, symmetry, output);
            runTrial(round, trial, "measured", mode, source, expected, sample,
                    board, symmetry, output);
        } else if ("density".equals(args[0]) && args.length == 3) {
            final RangeShardRouter router = RangeShardRouter
                    .fromSourceSample(sample, board, symmetry);
            density(round, "delta", expected, router,
                    KeyPageCodecs.longDeltaVarint());
            density(round, "rank", expected, router,
                    new BoardStateEncoding(board)
                            .codecForPopulation(49 - round));
        } else {
            throw usage();
        }
    }

    private static void coordinate(final Path corpus, final int round,
            final Path baselineJar, final Path output, final int trials,
            final List<String> modes) throws Exception {
        if (trials < 3 || trials > 10) {
            throw new IllegalArgumentException("Use 3 to 10 measured trials");
        }
        validateModes(modes);
        final Path candidateJar = origin(HestiaRoundStore.class);
        final Path benchmarkClasses = origin(RoundOptimizationBenchmark.class);
        if (!Files.isRegularFile(candidateJar)
                || !Files.isRegularFile(baselineJar)
                || candidateJar.toRealPath().equals(baselineJar.toRealPath())) {
            throw new IllegalArgumentException(
                    "Use distinct candidate and preserved baseline production jars"
                            + "; do not include target/classes on the classpath");
        }
        if (Files.isDirectory(benchmarkClasses) && Files.exists(benchmarkClasses
                .resolve("cz/coroptis/pegsolitaire/HestiaRoundStore.class"))) {
            throw new IllegalArgumentException(
                    "Benchmark classes must not contain production classes");
        }
        Files.createDirectories(output);
        final String java = Path
                .of(System.getProperty("java.home"), "bin", "java").toString();
        for (int trial = 1; trial <= trials; trial++) {
            final List<String> order = new ArrayList<>(modes);
            if (trial % 2 == 0) {
                Collections.reverse(order);
            }
            for (final String mode : order) {
                final Path jar = "baseline".equals(mode) ? baselineJar
                        : candidateJar;
                final Path log = Files.createTempFile(output,
                        "round-" + round + "-" + mode + "-" + trial + "-",
                        ".log");
                final String classpath = benchmarkClasses
                        + System.getProperty("path.separator") + jar;
                final ProcessBuilder command = new ProcessBuilder(java,
                        "--enable-native-access=ALL-UNNAMED", "-Xms2g",
                        "-Xmx2g", "-cp", classpath,
                        RoundOptimizationBenchmark.class.getName(), "child",
                        corpus.toString(), Integer.toString(round), mode,
                        output.toString(), Integer.toString(trial),
                        jar.toString());
                System.out.printf("starting mode=%s trial=%d log=%s%n", mode,
                        trial, log);
                final Process process = command.redirectErrorStream(true)
                        .redirectOutput(log.toFile()).start();
                final int exit;
                try {
                    exit = process.waitFor();
                } catch (InterruptedException exception) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw exception;
                }
                System.out.print(Files.readString(log));
                if (exit != 0) {
                    throw new IllegalStateException(
                            "Benchmark child failed; retained log: " + log);
                }
            }
        }
    }

    private static void runTrial(final int round, final int trial,
            final String phase, final String mode, final long[] source,
            final long[] expected, final RoundStateSample sample,
            final PegSolitaireBoard board, final BoardSymmetry symmetry,
            final Path output) throws IOException {
        Files.createDirectories(output);
        final Path directory = Files.createTempDirectory(output, "round-"
                + round + "-" + mode + "-" + trial + "-" + phase + "-");
        final HestiaRoundStore store = new HestiaRoundStore(49);
        final long start = System.nanoTime();
        final RangeShardRouter router = RangeShardRouter
                .fromSourceSample(sample, board, symmetry);
        final SenkuWriting<Long, NullValue> writing;
        final ParallelRoundProcessor processor;
        if ("baseline".equals(mode)) {
            // These are intentionally only old API calls. This branch is run
            // with the preserved production jar, not the candidate classes.
            writing = store.create(directory, router);
            processor = new ParallelRoundProcessor(board, symmetry, 8, 32);
        } else {
            final KeyPageCodec<Long> codec = "full".equals(mode)
                    ? new BoardStateEncoding(board)
                            .codecForPopulation(49 - round)
                    : KeyPageCodecs.longDeltaVarint();
            writing = store.create(directory, router, codec);
            processor = new ParallelRoundProcessor(board, symmetry, 8, 32, 4096,
                    "fast-only".equals(mode) ? 0 : 65_536);
        }
        final long setupEnd = System.nanoTime();
        final ParallelRoundProcessor.ProcessingResult processed;
        try (Stream<Entry<Long, NullValue>> entries = Arrays.stream(source)
                .mapToObj(key -> Entry.of(key, NULL))) {
            processed = processor.process(entries.iterator(), writing);
        }
        final long ingestEnd = System.nanoTime();
        final SortedStateSampler destinationSample = new SortedStateSampler(49);
        final long finishEnd;
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting()) {
            finishEnd = System.nanoTime();
            verify(ready, expected, destinationSample);
        }
        final long verifyEnd = System.nanoTime();
        final Path sidecar = directory
                .resolveSibling(directory.getFileName() + ".state-sample");
        RoundStateSampleFile.write(sidecar, destinationSample.snapshot());
        final long roundEnd = System.nanoTime();
        // This additional validation does not belong to the normal round path;
        // report its cost separately as well as the complete benchmark total.
        try (SenkuReady<Long, NullValue> reopened = store.open(directory)) {
            verify(reopened, expected, null);
        }
        final long end = System.nanoTime();
        final long submittedMoves = "baseline".equals(mode)
                ? processed.generatedMoves()
                : processed.submittedMoves();
        final long submittedTasks = "baseline".equals(mode)
                ? processed.processedStates()
                : processed.submittedTasks();
        long indexBytes = 0L;
        long partBytes = 0L;
        long files = 0L;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                    .toList()) {
                final long size = Files.size(file);
                indexBytes += size;
                if (file.getFileName().toString().endsWith(".chunk")) {
                    partBytes += size;
                }
                files++;
            }
        }
        final long sidecarBytes = Files.size(sidecar);
        System.out.printf("trial round=%d trial=%d phase=%s mode=%s source=%d "
                + "moves=%d puts=%d tasks=%d states=%d setup_ms=%.3f "
                + "ingest_ms=%.3f finish_ms=%.3f verify_ms=%.3f "
                + "sidecar_ms=%.3f round_ms=%.3f reopen_verify_ms=%.3f "
                + "total_ms=%.3f index_bytes=%d part_bytes=%d sidecar_bytes=%d "
                + "total_bytes=%d bytes_per_state=%.6f files=%d "
                + "verified=true reopened_verified=true directory=%s%n", round,
                trial, phase, mode, processed.processedStates(),
                processed.generatedMoves(), submittedMoves, submittedTasks,
                expected.length, millis(start, setupEnd),
                millis(setupEnd, ingestEnd), millis(ingestEnd, finishEnd),
                millis(finishEnd, verifyEnd), millis(verifyEnd, roundEnd),
                millis(start, roundEnd), millis(roundEnd, end),
                millis(start, end), indexBytes, partBytes, sidecarBytes,
                indexBytes + sidecarBytes,
                (indexBytes + sidecarBytes) / (double) expected.length, files,
                directory);
    }

    private static void verify(final SenkuReady<Long, NullValue> ready,
            final long[] expected, final SortedStateSampler sampler) {
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
                if (sampler != null) {
                    sampler.add(key);
                }
            }
            if (index != expected.length) {
                throw new IllegalStateException("Persisted frontier truncated");
            }
        }
    }

    private static void density(final int round, final String mode,
            final long[] keys, final RangeShardRouter router,
            final KeyPageCodec<Long> codec) {
        final int[] counts = new int[RangeShardRouter.SHARD_COUNT];
        for (final long key : keys) {
            counts[router.shard(key)]++;
        }
        final long[][] shards = new long[counts.length][];
        for (int shard = 0; shard < counts.length; shard++) {
            shards[shard] = new long[counts[shard]];
        }
        final int[] offsets = new int[counts.length];
        for (final long key : keys) {
            final int shard = router.shard(key);
            shards[shard][offsets[shard]++] = key;
        }
        long rawBytes = 0L;
        long payloadBytes = 0L;
        int pages = 0;
        for (final long[] shard : shards) {
            for (int start = 0; start < shard.length; start += PAGE_KEYS) {
                final int end = Math.min(shard.length, start + PAGE_KEYS);
                final SingleChunkEntryWriterImpl<Long, NullValue> writer = new SingleChunkEntryWriterImpl<>(
                        new TypeDescriptorLong(), new TypeDescriptorNull(),
                        PAGE_KEYS * 10, codec);
                for (int index = start; index < end; index++) {
                    writer.putLongKey(shard[index], NULL);
                }
                final var payload = writer.closeSequence();
                rawBytes += payload.length();
                final ChunkData compressed = new ChunkFilterZstdCompress(3)
                        .apply(ChunkData.ofSequence(0, 0,
                                ChunkHeader.MAGIC_NUMBER, codec.getId(),
                                payload));
                payloadBytes += compressed.getPayloadSequence().length();
                final ChunkData decoded = new ChunkFilterZstdDecompress()
                        .apply(compressed);
                final LongKeyPageReader reader = new LongKeyPageReader(codec);
                try (MemFileReader input = new MemFileReader(
                        decoded.getPayloadSequence().toByteArray())) {
                    for (int index = start; index < end; index++) {
                        final Long actual = reader.read(input);
                        if (actual == null
                                || actual.longValue() != shard[index]) {
                            throw new IllegalStateException(
                                    "Codec did not round-trip an exact state");
                        }
                    }
                    if (reader.read(input) != null) {
                        throw new IllegalStateException("Trailing encoded key");
                    }
                }
                pages++;
            }
        }
        System.out.printf(
                "density round=%d mode=%s states=%d raw_bytes=%d "
                        + "payload_bytes=%d bytes_per_state=%.6f "
                        + "states_per_byte=%.6f pages=%d verified=true%n",
                round, mode, keys.length, rawBytes, payloadBytes,
                payloadBytes / (double) keys.length,
                keys.length / (double) payloadBytes, pages);
    }

    private static long[] read(final Path file, final int population)
            throws IOException {
        final long size = Files.size(file);
        if (size % Long.BYTES != 0L || size / Long.BYTES > Integer.MAX_VALUE) {
            throw new IOException("Invalid or oversized corpus: " + file);
        }
        final long[] keys = new long[(int) (size / Long.BYTES)];
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            for (int index = 0; index < keys.length; index++) {
                keys[index] = input.readLong();
                if ((keys[index] >>> 49) != 0L
                        || Long.bitCount(keys[index]) != population
                        || (index > 0 && keys[index] <= keys[index - 1])) {
                    throw new IOException(
                            "Corpus must be sorted unique Senku states of "
                                    + population + " pegs: " + file);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Corpus changed while reading: " + file);
            }
        }
        return keys;
    }

    private static void validateModes(final List<String> modes) {
        if (modes.isEmpty() || !MODES.containsAll(modes)
                || modes.stream().distinct().count() != modes.size()) {
            throw new IllegalArgumentException(
                    "Choose distinct modes from " + MODES);
        }
    }

    private static Path origin(final Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toAbsolutePath();
    }

    private static double millis(final long start, final long end) {
        return (end - start) / 1_000_000.0;
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException(
                "run <corpus> <round> <baseline.jar> <output-parent> <trials> "
                        + "[baseline,fast-only,fast-cache,full]; "
                        + "density <corpus> <round>");
    }
}
