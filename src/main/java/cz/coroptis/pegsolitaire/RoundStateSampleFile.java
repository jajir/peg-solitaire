package cz.coroptis.pegsolitaire;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * Atomically persists an application-owned sample beside a Senku index. A
 * checksum and strict size/version checks reject partial or malformed files. A
 * missing sample can be rebuilt by streaming the completed source index.
 */
final class RoundStateSampleFile {

    private static final int MAGIC = 0x534b5153;
    private static final int VERSION = 1;
    private static final int FIXED_BYTES = 36;
    private static final int MAX_FILE_BYTES = FIXED_BYTES
            + RoundStateSample.MAX_SAMPLES * Long.BYTES;

    private RoundStateSampleFile() {
    }

    /** Reads an optional completed sample and validates its board geometry. */
    static Optional<RoundStateSample> read(final Path file,
            final int stateBitCount) throws IOException {
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        final long size = Files.size(file);
        if (size < FIXED_BYTES || size > MAX_FILE_BYTES) {
            throw new IOException("Invalid round sample file size: " + file);
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(file)) {
            // Bound allocation even if a file grows after the size check.
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length != size) {
            throw new IOException("Round sample changed during read: " + file);
        }
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) {
            throw new IOException("Unsupported round sample format: " + file);
        }
        final int bits = buffer.getInt();
        final long count = buffer.getLong();
        final long stride = buffer.getLong();
        final int sampleCount = buffer.getInt();
        if (bits != stateBitCount || sampleCount < 0
                || sampleCount > RoundStateSample.MAX_SAMPLES
                || bytes.length != FIXED_BYTES + sampleCount * Long.BYTES) {
            throw new IOException(
                    "Round sample has invalid board or count metadata: "
                            + file);
        }
        final CRC32C checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length - Integer.BYTES);
        if ((int) checksum.getValue() != buffer
                .getInt(bytes.length - Integer.BYTES)) {
            throw new IOException("Round sample checksum mismatch: " + file);
        }
        final long[] states = new long[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            states[index] = buffer.getLong();
        }
        try {
            return Optional
                    .of(new RoundStateSample(bits, count, stride, states));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid round sample: " + file, exception);
        }
    }

    /** Publishes the complete sidecar before the caller publishes its round. */
    static void write(final Path file, final RoundStateSample sample)
            throws IOException {
        final long[] states = sample.states();
        final ByteBuffer buffer = ByteBuffer
                .allocate(FIXED_BYTES + states.length * Long.BYTES);
        buffer.putInt(MAGIC).putInt(VERSION).putInt(sample.stateBitCount())
                .putLong(sample.stateCount()).putLong(sample.stride())
                .putInt(states.length);
        for (final long key : states) {
            buffer.putLong(key);
        }
        final CRC32C checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) checksum.getValue()).flip();
        final Path temporary = Files.createTempFile(
                file.toAbsolutePath().getParent(),
                "." + file.getFileName() + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE)) {
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }
}
