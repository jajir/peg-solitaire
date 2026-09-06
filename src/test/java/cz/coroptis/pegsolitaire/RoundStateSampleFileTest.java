package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundStateSampleFileTest {

    @Test
    void versionTwoPersistsExplicitWeightsAndRejectsCorruptedWeightSum()
            throws Exception {
        final Path file = temporaryDirectory.resolve("weighted.state-sample");
        final RoundStateSample source = RoundStateSample.fromWeighted(49, 100,
                new long[] { 3, 7 }, new long[] { 90, 10 });
        RoundStateSampleFile.write(file, source);
        final byte[] data = Files.readAllBytes(file);
        assertEquals(2, ByteBuffer.wrap(data).getInt(4));
        assertEquals(68, data.length);
        final RoundStateSample actual = RoundStateSampleFile.read(file, 49)
                .orElseThrow();
        assertTrue(actual.isWeighted());
        assertEquals(0, actual.stride());
        assertArrayEquals(source.states(), actual.states());
        assertArrayEquals(source.weights(), actual.weights());
        ByteBuffer.wrap(data).putLong(48, 89L);
        updateChecksum(data);
        Files.write(file, data);
        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));
    }

    @TempDir
    private Path temporaryDirectory;

    @Test
    void atomicallyWritesReadsAndReplacesBoundedSample() throws Exception {
        final Path file = temporaryDirectory.resolve("8.state-sample");
        assertTrue(RoundStateSampleFile.read(file, 49).isEmpty());
        final SortedStateSampler sampler = new SortedStateSampler(49);
        for (long key = 0; key < 10_000; key++) {
            sampler.add((1L << 48) + key);
        }
        RoundStateSampleFile.write(file, sampler.snapshot());

        final RoundStateSample read = RoundStateSampleFile.read(file, 49)
                .orElseThrow();
        assertEquals(49, read.stateBitCount());
        assertEquals(10_000L, read.stateCount());
        assertEquals(4L, read.stride());
        assertArrayEquals(sampler.snapshot().states(), read.states());
        assertTrue(Files.size(file) <= 32_804L);

        RoundStateSampleFile.write(file,
                new RoundStateSample(49, 0L, 1L, new long[0]));
        assertEquals(0L,
                RoundStateSampleFile.read(file, 49).orElseThrow().stateCount());
        assertEquals(36L, Files.size(file));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count());
        }
    }

    @Test
    void rejectsCorruptionTruncationUnknownVersionAndBoardMismatch()
            throws Exception {
        final Path file = writeSample();
        final byte[] original = Files.readAllBytes(file);
        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 33));

        final byte[] corrupt = original.clone();
        corrupt[32] ^= 1;
        Files.write(file, corrupt);
        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));

        Files.write(file, Arrays.copyOf(original, original.length - 1));
        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));

        final byte[] unknownVersion = original.clone();
        ByteBuffer.wrap(unknownVersion).putInt(4, 99);
        updateChecksum(unknownVersion);
        Files.write(file, unknownVersion);
        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));
    }

    @Test
    void rejectsInconsistentMetadataEvenWithValidChecksum() throws Exception {
        final Path file = writeSample();
        final byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putLong(12, 99L);
        updateChecksum(bytes);
        Files.write(file, bytes);

        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));
    }

    @Test
    void rejectsOutOfOrderStatesEvenWithValidChecksum() throws Exception {
        final Path file = writeSample();
        final byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putLong(40, 0L);
        updateChecksum(bytes);
        Files.write(file, bytes);

        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));
    }

    @Test
    void rejectsOversizedFileBeforeParsing() throws Exception {
        final Path file = temporaryDirectory.resolve("oversize.state-sample");
        Files.write(file, new byte[32_805]);

        assertThrows(IOException.class,
                () -> RoundStateSampleFile.read(file, 49));
    }

    @Test
    void failedPublicationCleansUpOnlyItsOwnTemporaryFile() throws Exception {
        final Path target = temporaryDirectory.resolve("blocked.state-sample");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "unrelated");

        assertThrows(IOException.class, () -> RoundStateSampleFile.write(target,
                new RoundStateSample(49, 0L, 1L, new long[0])));
        assertEquals("unrelated", Files.readString(target.resolve("keep")));
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count());
        }
    }

    private Path writeSample() throws IOException {
        final Path file = temporaryDirectory.resolve("sample.state-sample");
        RoundStateSampleFile.write(file,
                new RoundStateSample(49, 2L, 1L, new long[] { 1L, 2L }));
        return file;
    }

    private void updateChecksum(final byte[] bytes) {
        final CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length - Integer.BYTES);
        ByteBuffer.wrap(bytes).putInt(bytes.length - Integer.BYTES,
                (int) checksum.getValue());
    }
}
