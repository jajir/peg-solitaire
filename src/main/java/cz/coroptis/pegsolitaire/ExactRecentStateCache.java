package cz.coroptis.pegsolitaire;

import java.util.function.LongConsumer;

/**
 * Worker-confined, bounded cache of successfully submitted complete state keys.
 * A collision only evicts an old entry; it can never suppress a different key.
 */
final class ExactRecentStateCache {

    private final long[] keys;
    private final long[] occupied;

    /**
     * Creates a direct-mapped cache, or disables filtering when capacity is
     * zero.
     *
     * @param capacity zero or a positive power of two
     */
    ExactRecentStateCache(final int capacity) {
        if (capacity < 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "cache capacity must be zero or a positive power of two");
        }
        keys = new long[capacity];
        occupied = new long[(int) ((capacity + 63L) / Long.SIZE)];
    }

    /**
     * Sends an uncached key to the consumer and remembers it only if that call
     * succeeds. The occupancy bitmap permits zero and every other long value.
     *
     * @return true when the consumer was called, false for an exact cache hit
     */
    boolean submitIfAbsent(final long state, final LongConsumer consumer) {
        if (keys.length == 0) {
            consumer.accept(state);
            return true;
        }
        final int hash = mixedHash(state);
        if (contains(state, hash)) {
            return false;
        }
        consumer.accept(state);
        rememberSuccessful(state, hash);
        return true;
    }

    /** Returns whether this cache filters successfully submitted keys. */
    boolean isEnabled() {
        return keys.length != 0;
    }

    /**
     * Checks an exact committed key using its already computed mixed hash.
     * Pending buffered writes must be tracked separately from this cache.
     */
    boolean contains(final long state, final int hash) {
        if (keys.length == 0) {
            return false;
        }
        final int slot = hash & (keys.length - 1);
        return (occupied[slot >>> 6] & (1L << (slot & 63))) != 0L
                && keys[slot] == state;
    }

    /**
     * Records a complete key only after its destination write succeeds. The
     * supplied hash must come from {@link #mixedHash(long)}.
     */
    void rememberSuccessful(final long state, final int hash) {
        if (keys.length == 0) {
            return;
        }
        final int slot = hash & (keys.length - 1);
        keys[slot] = state;
        occupied[slot >>> 6] |= 1L << (slot & 63);
    }

    /** Mixes a key once for both pending membership and committed caching. */
    static int mixedHash(final long state) {
        long mixed = state;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return (int) mixed;
    }
}
