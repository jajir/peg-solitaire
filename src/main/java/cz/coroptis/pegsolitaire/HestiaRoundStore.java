package cz.coroptis.pegsolitaire;

import java.io.File;
import java.nio.file.Path;

import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.directory.FsDirectory;
import org.hestiastore.index.segmentindex.SegmentIndex;
import org.hestiastore.index.segmentindex.configuration.api.IndexConfiguration;
import org.hestiastore.index.segmentindex.configuration.tuning.RuntimeTuningPatch;
import org.hestiastore.index.segmentindex.configuration.tuning.RuntimeTuningResult;

/**
 * Creates and opens the Hestia indexes used for round frontiers.
 */
public final class HestiaRoundStore {

    private static final int CHUNK_KEY_LIMIT = 30_000;
    private static final String INDEX_NAME = "peg-solitaire-round";
    private static final String READ_INDEX_NAME = INDEX_NAME + "-reader";
    private static final int READ_CACHED_SEGMENT_LIMIT = 3;
    private static final int READ_CACHE_KEY_LIMIT = 5_000;
    private static final int READ_CHUNK_PAGE_LIMIT = 5;

    /**
     * Creates an empty round index.
     *
     * @param directory target index directory
     * @return opened new index
     */
    public SegmentIndex<Long, NullValue> create(final Path directory) {
        return SegmentIndex.create(new FsDirectory(asFile(directory)),
                writeConfiguration());
    }

    /**
     * Opens a completed round index.
     *
     * @param directory existing index directory
     * @return opened index
     */
    public SegmentIndex<Long, NullValue> open(final Path directory) {
        final SegmentIndex<Long, NullValue> index = SegmentIndex.open(
                new FsDirectory(asFile(directory)), readConfiguration());
        final RuntimeTuningResult result = index.runtimeTuning()
                .apply(RuntimeTuningPatch.builder()
                        .cachedSegmentLimit(READ_CACHED_SEGMENT_LIMIT)
                        .cacheKeyLimit(CHUNK_KEY_LIMIT)
                        .chunkStoreCachePageLimit(READ_CHUNK_PAGE_LIMIT)
                        .build());
        if (!result.applied()) {
            index.close();
            throw new IllegalStateException(
                    "Unable to apply the Hestia read-index profile: "
                            + result.validation().issues());
        }
        return index;
    }

    private IndexConfiguration<Long, NullValue> writeConfiguration() {
        return IndexConfiguration.<Long, NullValue>builder()//
                .identity(identity -> identity.keyClass(Long.class))//
                .identity(identity -> identity.valueClass(NullValue.class))//
                .identity(identity -> identity.name(INDEX_NAME))//
                .wal(wal -> wal.disabled())//
                .segment(segment -> segment//
                        .cacheKeyLimit(1_000_001)//
                        .chunkKeyLimit(CHUNK_KEY_LIMIT)//
                        .maxKeys(10_000_000)//
                        .cachedSegmentLimit(20))//
                .writePath(writePath -> writePath//
                        .segmentWriteCacheKeyLimit(1_000_000)//
                        .maintenanceWriteCacheKeyLimit(1_010_000))//
                .bloomFilter(bloomFilter -> bloomFilter//
                        .indexSizeBytes(0)//
                        .hashFunctions(3))//
                .maintenance(maintenance -> maintenance// 
                        .indexThreads(10)//
                        .busyBackoffMillis(5)//
                        .busyTimeoutMillis(900_000)//
                        .backgroundAutoEnabled(true)//
                        .registryLifecycleThreads(4))//
                .io(io -> io//
                        .diskBufferSizeBytes(1024 * 16))//
                .logging(logging -> logging//
                        .contextEnabled(Boolean.TRUE))//
                .chunkStoreCache(chunkCache -> chunkCache//
                    .pageLimit(1_000))//
           .build();
    }

    private IndexConfiguration<Long, NullValue> readConfiguration() {
        return IndexConfiguration.<Long, NullValue>builder()//
                .identity(identity -> identity.keyClass(Long.class))//
                .identity(identity -> identity.valueClass(NullValue.class))//
                .identity(identity -> identity.name(READ_INDEX_NAME))//
                .segment(segment -> segment//
                        .cacheKeyLimit(READ_CACHE_KEY_LIMIT))//
                .maintenance(maintenance -> maintenance//
                        .indexThreads(1)//
                        .busyBackoffMillis(5)//
                        .busyTimeoutMillis(900_000)//
                        .backgroundAutoEnabled(false)//
                        .registryLifecycleThreads(1))//
                .io(io -> io//
                        .diskBufferSizeBytes(1024 * 16))//
                .logging(logging -> logging//
                        .contextEnabled(Boolean.TRUE))//
                .chunkStoreCache(chunkCache -> chunkCache//
                        .pageLimit(READ_CHUNK_PAGE_LIMIT))//
                .build();
    }

    private File asFile(final Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        return directory.toFile();
    }
}
