package cz.coroptis.pegsolitaire;

import static org.hestiastore.index.datatype.NullValue.NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
