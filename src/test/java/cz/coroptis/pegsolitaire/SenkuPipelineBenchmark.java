package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;

/**
 * Opt-in round-12 comparison against the pre-pipeline checkpoint production
 * jar. Both modes use identical ranked pages, source keys, routing, kernel,
 * worker counts and cache sizes. Only the candidate uses ready metadata. Run in
 * isolated JVMs with exactly one production jar on the classpath.
 */
public final class SenkuPipelineBenchmark {
    private SenkuPipelineBenchmark() {
    }

    /**
     * Runs one warmup and one measured round. Complete persisted-key
     * verification happens after the production timing boundary for both modes.
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3 || !("baseline".equals(args[2])
                || "candidate".equals(args[2]))) {
            throw new IllegalArgumentException(
                    "<corpus directory> <output parent> <baseline|candidate>");
        }
        Locale.setDefault(Locale.ROOT);
        final long[] source = read(Path.of(args[0], "senku-round-11.longs"),
                38);
        final long[] expected = read(Path.of(args[0], "senku-round-12.longs"),
                37);
        final SortedStateSampler sampler = new SortedStateSampler(49);
        for (final long key : source) {
            sampler.add(key);
        }
        final Path output = Path.of(args[1]);
        Files.createDirectories(output);
        System.out.printf("environment java=%s heap=%d production=%s%n",
                System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory(), HestiaRoundStore.class
                        .getProtectionDomain().getCodeSource().getLocation());
        run(source, expected, sampler.snapshot(), output, args[2], "warmup");
        run(source, expected, sampler.snapshot(), output, args[2], "measured");
    }

    private static void run(final long[] source, final long[] expected,
            final RoundStateSample sourceSample, final Path output,
            final String mode, final String phase) throws Exception {
        final Path directory = Files.createTempDirectory(output,
                mode + "-" + phase + "-");
        final SenkuBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final HestiaRoundStore store = new HestiaRoundStore(49);
        final long start = System.nanoTime();
        final RangeShardRouter router = RangeShardRouter
                .fromSourceSample(sourceSample, board, symmetry);
        final SenkuWriting<Long, NullValue> writing = store.create(directory,
                router, new BoardStateEncoding(board).codecForPopulation(37));
        final ParallelRoundProcessor processor = new ParallelRoundProcessor(
                board, symmetry, 8, 32, 4096, 65_536);
        final long setupEnd = System.nanoTime();
        final ParallelRoundProcessor.ProcessingResult processing;
        try (Stream<Entry<Long, NullValue>> entries = Arrays.stream(source)
                .mapToObj(key -> Entry.of(key, NULL))) {
            processing = processor.process(entries.iterator(), writing);
        }
        final long ingestEnd = System.nanoTime();
        final long finishEnd;
        final RoundStateSample sample;
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting()) {
            finishEnd = System.nanoTime();
            if ("candidate".equals(mode)) {
                sample = metadataSample(ready);
            } else {
                final SortedStateSampler sampler = new SortedStateSampler(49);
                try (Stream<Entry<Long, NullValue>> entries = ready
                        .openStream()) {
                    entries.forEach(entry -> sampler.add(entry.getKey()));
                }
                sample = sampler.snapshot();
            }
        }
        final Path sidecar = directory
                .resolveSibling(directory.getFileName() + ".state-sample");
        RoundStateSampleFile.write(sidecar, sample);
        final long roundEnd = System.nanoTime();
        if (sample.stateCount() != expected.length) {
            throw new IllegalStateException(
                    "Metadata count differs from exact corpus");
        }
        try (SenkuReady<Long, NullValue> ready = store.open(directory);
                Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
            final Iterator<Entry<Long, NullValue>> iterator = entries
                    .iterator();
            int index = 0;
            while (iterator.hasNext()) {
                if (index >= expected.length
                        || iterator.next().getKey() != expected[index++]) {
                    throw new IllegalStateException(
                            "Persisted frontier differs from exact corpus");
                }
            }
            if (index != expected.length) {
                throw new IllegalStateException(
                        "Persisted frontier is incomplete");
            }
        }
        final long verifiedEnd = System.nanoTime();
        long indexBytes = 0L;
        try (Stream<Path> files = Files.walk(directory)) {
            for (final Path file : files.filter(Files::isRegularFile)
                    .toList()) {
                indexBytes += Files.size(file);
            }
        }
        System.out.printf(
                "trial mode=%s phase=%s source=%d moves=%d puts=%d states=%d "
                        + "setup_ms=%.3f ingest_ms=%.3f finish_ms=%.3f sample_ms=%.3f "
                        + "round_ms=%.3f verify_ms=%.3f total_ms=%.3f index_bytes=%d "
                        + "sidecar_bytes=%d bytes_per_state=%.6f verified=true directory=%s%n",
                mode, phase, source.length, processing.generatedMoves(),
                processing.submittedMoves(), sample.stateCount(),
                ms(start, setupEnd), ms(setupEnd, ingestEnd),
                ms(ingestEnd, finishEnd), ms(finishEnd, roundEnd),
                ms(start, roundEnd), ms(roundEnd, verifiedEnd),
                ms(start, verifiedEnd), indexBytes, Files.size(sidecar),
                indexBytes / (double) expected.length, directory);
    }

    private static RoundStateSample metadataSample(
            final SenkuReady<Long, NullValue> ready)
            throws ReflectiveOperationException {
        // One metadata conversion, never per-key reflection. This keeps the
        // benchmark loadable with the checkpoint jar, which lacks this API.
        final Optional<?> summary = (Optional<?>) SenkuReady.class
                .getMethod("longKeySummary").invoke(ready);
        final Object value = summary.orElseThrow();
        final Class<?> type = value.getClass();
        final long count = (Long) type.getMethod("recordCount").invoke(value);
        final long[] keys = (long[]) type.getMethod("keys").invoke(value);
        final long[] weights = (long[]) type.getMethod("weights").invoke(value);
        final var factory = RoundStateSample.class.getDeclaredMethod(
                "fromWeighted", int.class, long.class, long[].class,
                long[].class);
        return (RoundStateSample) factory.invoke(null, 49, count, keys,
                weights);
    }

    private static long[] read(final Path file, final int population)
            throws IOException {
        final long length = Files.size(file);
        if (length % Long.BYTES != 0
                || length / Long.BYTES > Integer.MAX_VALUE) {
            throw new IOException("Invalid corpus length");
        }
        final long[] keys = new long[(int) (length / Long.BYTES)];
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            for (int index = 0; index < keys.length; index++) {
                keys[index] = input.readLong();
                if ((keys[index] >>> 49) != 0
                        || Long.bitCount(keys[index]) != population
                        || (index > 0 && keys[index] <= keys[index - 1])) {
                    throw new IOException("Invalid corpus key");
                }
            }
        }
        return keys;
    }

    private static double ms(final long start, final long end) {
        return (end - start) / 1_000_000.0;
    }
}
