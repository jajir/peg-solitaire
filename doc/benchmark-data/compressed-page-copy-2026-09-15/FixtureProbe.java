package org.hestiastore.benchmark.chunkstore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import org.hestiastore.index.chunkstore.ChunkFilterCrc32Validation;
import org.hestiastore.index.chunkstore.ChunkFilterZstdDecompress;
import org.hestiastore.index.chunkstore.ChunkStoreFile;
import org.hestiastore.index.chunkstore.ChunkStoreReader;
import org.hestiastore.index.datablockfile.DataBlockSize;
import org.hestiastore.index.directory.FileWriter;
import org.hestiastore.index.directory.MemDirectory;

/** Standalone cross-build storage compatibility probe, outside JMH timing. */
public final class FixtureProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        Files.createDirectories(directory);
        for (int size : new int[] {4096, 65536}) {
            Path fixture = directory.resolve("page-" + size + ".bin");
            if ("write".equals(args[0])) {
                ChunkStoreZstdReadBenchmark benchmark = new ChunkStoreZstdReadBenchmark();
                benchmark.payloadSize = size;
                benchmark.setup();
                Files.write(fixture, benchmark.persistedBytes);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(benchmark.persistedBytes));
                System.out.println("size=" + size + " compressed=" + benchmark.compressedBytes
                        + " stored=" + benchmark.persistedBytes.length + " sha256=" + hash);
            } else {
                MemDirectory memory = new MemDirectory();
                try (FileWriter writer = memory.getFileWriter("fixture")) {
                    writer.write(Files.readAllBytes(fixture));
                }
                ChunkStoreFile store = new ChunkStoreFile(memory, "fixture",
                        DataBlockSize.ofDataBlockSize(8192), List.of(),
                        List.of(new ChunkFilterCrc32Validation(), new ChunkFilterZstdDecompress()));
                byte[] expected = new byte[size];
                new Random(42).nextBytes(expected);
                System.arraycopy(expected, 0, expected, size / 2, size / 2);
                try (ChunkStoreReader reader = store.openReader(store.getFirstChunkStorePosition())) {
                    if (!Arrays.equals(expected, reader.readPayloadSequence().toByteArray())
                            || reader.readPayloadSequence() != null) {
                        throw new IllegalStateException("Cross-build fixture differs: " + size);
                    }
                }
                System.out.println("cross-build read passed size=" + size);
            }
        }
    }
}
