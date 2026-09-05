package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
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
            assertEquals(List.of(7L, 12L),
                    entries.map(Entry::getKey).toList());
        }
        assertTrue(Files.isRegularFile(indexDirectory.resolve(
                "ready.properties")));

        try (SenkuReady<Long, NullValue> index = store.open(indexDirectory);
                Stream<Entry<Long, NullValue>> entries = index.openStream()) {
            assertEquals(List.of(7L, 12L),
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
            final int shard = Math.floorMod(
                    store.shardHash(boardState), SHARD_COUNT);
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
