package cz.coroptis.pegsolitaire;

import java.io.File;
import java.nio.file.Path;

import org.hestiastore.index.datatype.NullValue;
import org.hestiastore.index.directory.FsDirectory;
import org.hestiastore.index.segmentindex.SegmentIndex;
import org.hestiastore.index.segmentindex.configuration.api.IndexConfiguration;

/**
 * Creates and opens the Hestia indexes used for round frontiers.
 */
public final class HestiaRoundStore {

    private static final String INDEX_NAME = "peg-solitaire-round";

    /**
     * Creates an empty round index.
     *
     * @param directory target index directory
     * @return opened new index
     */
    public SegmentIndex<Long, NullValue> create(final Path directory) {
        return SegmentIndex.create(new FsDirectory(asFile(directory)),
                configuration());
    }

    /**
     * Opens a completed round index.
     *
     * @param directory existing index directory
     * @return opened index
     */
    public SegmentIndex<Long, NullValue> open(final Path directory) {
        return SegmentIndex.open(new FsDirectory(asFile(directory)),
                configuration());
    }

    private IndexConfiguration<Long, NullValue> configuration() {
        return IndexConfiguration.<Long, NullValue>builder()
                .identity(identity -> identity.keyClass(Long.class))
                .identity(identity -> identity.valueClass(NullValue.class))
                .identity(identity -> identity.name(INDEX_NAME))
                .build();
    }

    private File asFile(final Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        return directory.toFile();
    }
}
