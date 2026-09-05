package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.chunkentryfile.KeyPageCodecs;
import org.hestiastore.index.chunkstore.Compression;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.datatype.TypeDescriptorLong;
import org.hestiastore.index.datatype.TypeDescriptorNull;
import org.hestiastore.index.directory.MemDirectory;
import org.hestiastore.index.senku.SenkuIndex;
import org.hestiastore.index.senku.SenkuMergeFunctionRegistry;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HestiaRoundStoreTest {

    private static final int SHARD_COUNT = 128;

    @TempDir
    private Path temporaryDirectory;

    @Test
    void longNullValueIndexFinalizesAndReopens() throws Exception {
        final Path indexDirectory = temporaryDirectory.resolve("round");
        Files.createDirectory(indexDirectory);
        final HestiaRoundStore store = new HestiaRoundStore();
        final SenkuWriting<Long, NullValue> writing = store
                .create(indexDirectory);
        writing.put(12L, NULL);
        writing.put(7L, NULL);
        writing.put(12L, NULL);
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting();
                Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
            assertEquals(List.of(7L, 12L), entries.map(Entry::getKey).toList());
        }
        assertTrue(Files
                .isRegularFile(indexDirectory.resolve("ready.properties")));
        final Properties format = new Properties();
        try (var input = Files
                .newInputStream(indexDirectory.resolve("format.properties"))) {
            format.load(input);
        }
        assertEquals("3", format.getProperty("keyPageCodec"));
        assertEquals("zstd", format.getProperty("compression"));
        assertEquals("3", format.getProperty("compressionLevel"));

        try (SenkuReady<Long, NullValue> index = store.open(indexDirectory);
                Stream<Entry<Long, NullValue>> entries = index.openStream()) {
            assertEquals(List.of(7L, 12L), entries.map(Entry::getKey).toList());
        }
    }

    @Test
    void rangeShardedIndexDeduplicatesAndReopensWithoutRouter()
            throws Exception {
        final Path directory = temporaryDirectory.resolve("ranges");
        Files.createDirectory(directory);
        final HestiaRoundStore store = new HestiaRoundStore(49);
        final RangeShardRouter router = new RangeShardRouter(
                new long[] { 10L, 1L << 48 });
        final SenkuWriting<Long, NullValue> writing = store.create(directory,
                router);
        final List<Long> expected = List.of(1L, 9L, 10L, 999L, 1L << 48,
                (1L << 49) - 1);
        for (int index = expected.size() - 1; index >= 0; index--) {
            writing.put(expected.get(index), NULL);
            writing.put(expected.get(index), NULL);
        }
        try (SenkuReady<Long, NullValue> ready = writing.finishWriting();
                Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
            assertEquals(expected, entries.map(Entry::getKey).toList());
        }

        try (SenkuReady<Long, NullValue> ready = new HestiaRoundStore()
                .open(directory);
                Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
            assertEquals(expected, entries.map(Entry::getKey).toList());
        }
    }

    @Test
    void immutableRangesSurviveMultipleFlushesAndDuplicateMerges()
            throws Exception {
        final MemDirectory directory = new MemDirectory();
        final SenkuMergeFunctionRegistry<Long, NullValue> functions = new SenkuMergeFunctionRegistry<>();
        functions.register((key, first, second) -> NULL);
        final RangeShardRouter router = new RangeShardRouter(
                new long[] { 256L, 512L, 768L });
        final SenkuWriting<Long, NullValue> writing = SenkuIndex
                .builder(directory, new TypeDescriptorLong(),
                        new TypeDescriptorNull(), functions)
                .keyPageCodec(KeyPageCodecs.longDeltaVarint())
                .compression(Compression.zstd(3)).shardHashFunction(router)
                .shardCount(SHARD_COUNT).maxInMemoryEntries(128)
                .maxKeysPerPage(32).maxEntriesPerPart(2048).mergeFanIn(64)
                .maintenanceThreads(2).maintenanceQueueSize(128).create();
        for (int pass = 0; pass < 2; pass++) {
            for (long key = 1023; key >= 0; key--) {
                writing.put(key, NULL);
            }
        }
        try (SenkuReady<Long, NullValue> ignored = writing.finishWriting()) {
            // Small buffers force multiple flushes before immutable
            // publication.
        }
        try (SenkuReady<Long, NullValue> ready = SenkuIndex.open(directory,
                new TypeDescriptorLong(), new TypeDescriptorNull(), 8192);
                Stream<Entry<Long, NullValue>> entries = ready.openStream()) {
            assertEquals(LongStream.range(0, 1024).boxed().toList(),
                    entries.map(Entry::getKey).toList());
        }
    }

    @Test
    void shardHashMixesBoardPrefixesEvenly() {
        final HestiaRoundStore store = new HestiaRoundStore(
                SenkuBoard.HOLE_COUNT);
        final int[] shardSizes = new int[SHARD_COUNT];
        for (long prefix = 0; prefix < 1_000_000L; prefix++) {
            final long boardState = prefix << 24;
            final int shard = Math.floorMod(store.shardHash(boardState),
                    SHARD_COUNT);
            shardSizes[shard]++;
        }

        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        for (final int shardSize : shardSizes) {
            minimum = Math.min(minimum, shardSize);
            maximum = Math.max(maximum, shardSize);
        }

        final int observedMinimum = minimum;
        final int observedMaximum = maximum;
        assertTrue(observedMaximum < observedMinimum * 1.1,
                () -> "Uneven mixed shard sizes: min=" + observedMinimum
                        + ", max=" + observedMaximum);
    }

    @Test
    void statesWithSameSignificantPrefixUseSameShard() {
        final HestiaRoundStore store = new HestiaRoundStore(
                SenkuBoard.HOLE_COUNT);
        final long prefix = 0x123456L;
        final long first = prefix << 24;
        final long second = first | 0xffffffL;

        assertEquals(store.shardHash(first), store.shardHash(second));
    }

    @Test
    void stateBitCountMustFitInLong() {
        assertThrows(IllegalArgumentException.class,
                () -> new HestiaRoundStore(0));
        assertThrows(IllegalArgumentException.class,
                () -> new HestiaRoundStore(65));
    }
}
