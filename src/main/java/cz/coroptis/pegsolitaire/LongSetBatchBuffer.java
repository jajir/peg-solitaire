package cz.coroptis.pegsolitaire;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongConsumer;

import org.hestiastore.index.senku.SenkuLongSetWriting;

/**
 * Worker-confined child-key buffer for synchronous primitive-set writes. Exact
 * pending membership is separate from the successfully submitted recent cache.
 * The reusable arrays never escape an active destination call.
 */
final class LongSetBatchBuffer implements LongConsumer {

    static final int DEFAULT_CAPACITY = 4096;

    private final SenkuLongSetWriting destination;
    private final ExactRecentStateCache cache;
    private final long[] keys;
    private final int[] hashes;
    private final int[] pendingSlots;
    private int size;
    private long submittedKeys;
    private Throwable failure;

    /**
     * Creates a bounded child buffer; smaller capacities support focused tests.
     * A disabled recent cache also disables pending duplicate filtering.
     */
    LongSetBatchBuffer(final SenkuLongSetWriting destination,
            final ExactRecentStateCache cache, final int capacity) {
        this.destination = Objects.requireNonNull(destination, "destination");
        this.cache = Objects.requireNonNull(cache, "cache");
        if (capacity < 1 || capacity > DEFAULT_CAPACITY) {
            throw new IllegalArgumentException(
                    "child buffer capacity must be between 1 and 4096");
        }
        keys = new long[capacity];
        hashes = new int[cache.isEnabled() ? capacity : 0];
        // Slot zero means absent; other values refer to buffer index + 1.
        // A table at least twice the buffer capacity always has empty slots.
        pendingSlots = new int[cache.isEnabled()
                ? Integer.highestOneBit(capacity * 2 - 1) << 1
                : 0];
    }

    /** Accepts one child, flushing a full buffer before returning. */
    @Override
    public void accept(final long key) {
        requireHealthy();
        if (cache.isEnabled()) {
            final int hash = ExactRecentStateCache.mixedHash(key);
            if (cache.contains(key, hash)) {
                return;
            }
            final int pendingSlot = findPendingSlot(key, hash);
            if (pendingSlots[pendingSlot] != 0) {
                return;
            }
            hashes[size] = hash;
            pendingSlots[pendingSlot] = size + 1;
        }
        keys[size++] = key;
        if (size == keys.length) {
            flush();
        }
    }

    /**
     * Drains a full or partial buffer synchronously. Only a successful complete
     * call updates committed cache entries and submitted-key accounting. A
     * failed call may have accepted a non-prefix subset; the buffer cannot be
     * retried or reused after that failure.
     */
    void flush() {
        requireHealthy();
        if (size == 0) {
            return;
        }
        try {
            destination.putLongs(keys, 0, size);
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        }
        if (cache.isEnabled()) {
            for (int index = 0; index < size; index++) {
                cache.rememberSuccessful(keys[index], hashes[index]);
            }
            Arrays.fill(pendingSlots, 0);
        }
        submittedKeys += size;
        size = 0;
    }

    /** Returns keys accepted by completed destination calls only. */
    long submittedKeys() {
        return submittedKeys;
    }

    private int findPendingSlot(final long key, final int hash) {
        final int mask = pendingSlots.length - 1;
        int slot = hash & mask;
        while (pendingSlots[slot] != 0 && keys[pendingSlots[slot] - 1] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    private void requireHealthy() {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
