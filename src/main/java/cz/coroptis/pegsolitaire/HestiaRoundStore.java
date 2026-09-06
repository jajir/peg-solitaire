package cz.coroptis.pegsolitaire;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongToIntFunction;

import org.hestiastore.index.chunkentryfile.KeyPageCodec;
import org.hestiastore.index.chunkentryfile.KeyPageCodecs;
import org.hestiastore.index.chunkstore.Compression;
import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.datatype.TypeDescriptorLong;
import org.hestiastore.index.datatype.TypeDescriptorNull;
import org.hestiastore.index.directory.FsDirectory;
import org.hestiastore.index.senku.SenkuIndex;
import org.hestiastore.index.senku.SenkuMergeFunctionRegistry;
import org.hestiastore.index.senku.SenkuMergeFunctions;
import org.hestiastore.index.senku.SenkuReady;
import org.hestiastore.index.senku.SenkuWriting;

/**
 * Creates and opens the Senku indexes used for round frontiers.
 */
public final class HestiaRoundStore {

    private static final int DISK_BUFFER_SIZE_BYTES = 8_192;
    private static final int MAINTENANCE_THREADS = 8;
    private static final int MAX_IN_MEMORY_ENTRIES = 10_000_000;
    private static final long MAX_ENTRIES_PER_PART = 10_000_000L;
    private static final int MAX_KEYS_PER_PAGE = 1_000_000;
    private static final int MERGE_FAN_IN = 64;
    private static final int SHARD_COUNT = RangeShardRouter.SHARD_COUNT;
    /** Number of active high-order bits kept together for prefix encoding. */
    private static final int SHARD_PREFIX_BITS = 25;
    private static final int MAINTENANCE_QUEUE_SIZE = SHARD_COUNT
            - MAINTENANCE_THREADS;

    private final int shardPrefixShift;

    /**
     * Creates a store for general 64-bit keys.
     */
    public HestiaRoundStore() {
        this(Long.SIZE);
    }

    /**
     * Creates a store whose shard routing uses the significant state bits.
     *
     * @param stateBitCount number of bits occupied by an encoded board state
     */
    public HestiaRoundStore(final int stateBitCount) {
        if (stateBitCount < 1 || stateBitCount > Long.SIZE) {
            throw new IllegalArgumentException(
                    "stateBitCount must be between 1 and 64");
        }
        shardPrefixShift = Math.max(0, stateBitCount - SHARD_PREFIX_BITS);
    }

    /**
     * Creates an empty writable round index with legacy prefix-hash routing.
     * The round enumerator uses forecast ranges instead, when a source sample
     * is available.
     *
     * @param directory target index directory
     * @return new write-only index handle
     */
    public SenkuWriting<Long, NullValue> create(final Path directory) {
        return create(directory, this::shardHash,
                KeyPageCodecs.longDeltaVarint());
    }

    /** Creates a round with immutable, source-forecast numeric shard ranges. */
    SenkuWriting<Long, NullValue> create(final Path directory,
            final RangeShardRouter router) {
        return create(directory, router, KeyPageCodecs.longDeltaVarint());
    }

    /**
     * Creates a round whose immutable key domain is persisted with the index.
     */
    SenkuWriting<Long, NullValue> create(final Path directory,
            final RangeShardRouter router, final KeyPageCodec<Long> codec) {
        return create(directory,
                (LongToIntFunction) Objects.requireNonNull(router, "router"),
                Objects.requireNonNull(codec, "codec"));
    }

    private SenkuWriting<Long, NullValue> create(final Path directory,
            final LongToIntFunction shardHashFunction,
            final KeyPageCodec<Long> codec) {
        final SenkuMergeFunctionRegistry<Long, NullValue> functions = new SenkuMergeFunctionRegistry<>();
        functions.register(SenkuMergeFunctions.longSet());
        return SenkuIndex
                .builder(new FsDirectory(asFile(directory)),
                        new TypeDescriptorLong(), new TypeDescriptorNull(),
                        functions)
                .keyPageCodec(codec) //
                .compression(Compression.zstd(3)) //
                .shardCount(SHARD_COUNT) //
                .maxInMemoryEntries(MAX_IN_MEMORY_ENTRIES) //
                .maxKeysPerPage(MAX_KEYS_PER_PAGE) //
                .mergeFanIn(MERGE_FAN_IN) //
                .maintenanceThreads(MAINTENANCE_THREADS) //
                .maintenanceQueueSize(MAINTENANCE_QUEUE_SIZE) //
                .diskIoBufferSize(DISK_BUFFER_SIZE_BYTES) //
                .maxEntriesPerPart(MAX_ENTRIES_PER_PART) //
                .createLongSet(shardHashFunction);
    }

    /**
     * Opens a completed round index for streaming.
     *
     * @param directory existing ready index directory
     * @return exclusive read-only index handle
     */
    public SenkuReady<Long, NullValue> open(final Path directory) {
        return SenkuIndex.open(new FsDirectory(asFile(directory)),
                new TypeDescriptorLong(), new TypeDescriptorNull(),
                DISK_BUFFER_SIZE_BYTES);
    }

    /**
     * Selects the significant state prefix and mixes it before Senku chooses a
     * shard. States with the same prefix always reach the same shard. Mixing
     * the prefix avoids concentrating biased canonical board prefixes in only a
     * few power-of-two shards.
     *
     * @param value encoded board state
     * @return mixed 32-bit shard hash
     */
    int shardHash(final long value) {
        return mixShardPrefix(value >>> shardPrefixShift);
    }

    private static int mixShardPrefix(final long prefix) {
        long mixed = prefix;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return (int) (mixed ^ mixed >>> 32);
    }

    private File asFile(final Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        return directory.toFile();
    }
}
