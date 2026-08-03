package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.hestiastore.index.Entry;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.segmentindex.SegmentIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HestiaRoundStoreTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void longNullValueIndexSurvivesCompactionAndReopen() throws Exception {
        final Path indexDirectory = temporaryDirectory.resolve("round");
        Files.createDirectory(indexDirectory);
        final HestiaRoundStore store = new HestiaRoundStore();
        try (SegmentIndex<Long, NullValue> index = store.create(indexDirectory)) {
            index.put(12L, NULL);
            index.put(7L, NULL);
            index.put(12L, NULL);
            index.maintenance().compactAndWait();
        }

        try (SegmentIndex<Long, NullValue> index = store.open(indexDirectory);
                Stream<Entry<Long, NullValue>> entries = index.getStream()) {
            assertEquals("peg-solitaire-round-reader",
                    index.runtimeMonitoring().snapshot().indexName());
            assertEquals(3, index.runtimeTuning().current().segment()
                    .cachedSegmentLimit());
            assertEquals(30_000, index.runtimeTuning().current().segment()
                    .cacheKeyLimit());
            assertEquals(5, index.runtimeTuning().current().chunkStoreCache()
                    .pageLimit());
            assertEquals(List.of(7L, 12L),
                    entries.map(Entry::getKey).sorted().toList());
        }
    }
}
