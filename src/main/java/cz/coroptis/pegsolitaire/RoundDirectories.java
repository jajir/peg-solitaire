package cz.coroptis.pegsolitaire;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers completed rounds and safely manages temporary output directories.
 */
public final class RoundDirectories {

    private static final Pattern ROUND_NAME = Pattern.compile("[1-9][0-9]*");
    private static final String IN_PROGRESS_SUFFIX = ".in-progress";

    private final Path root;

    /**
     * Creates a round-directory manager rooted at {@code root}.
     *
     * @param root persistent round root
     */
    public RoundDirectories(final Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * Creates the data root if necessary.
     *
     * @throws IOException when the directory cannot be created
     */
    public void ensureRootExists() throws IOException {
        Files.createDirectories(root);
    }

    /**
     * Finds the greatest strictly numeric completed-round directory.
     *
     * @return latest round number, or empty when none exists
     * @throws IOException when the root cannot be listed
     */
    public OptionalInt latestCompletedRound() throws IOException {
        final List<Integer> rounds = completedRounds();
        if (rounds.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(rounds.get(rounds.size() - 1));
    }

    /**
     * Lists strictly numeric completed-round directories in ascending order.
     *
     * @return sorted completed round numbers
     * @throws IOException when the root cannot be listed
     */
    public List<Integer> completedRounds() throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> ROUND_NAME.matcher(name).matches())
                    .mapToInt(this::parseRound)
                    .filter(round -> round > 0)
                    .sorted()
                    .boxed()
                    .toList();
        }
    }

    public Path completed(final int round) {
        validateRound(round);
        return root.resolve(Integer.toString(round));
    }

    public Path inProgress(final int round) {
        validateRound(round);
        return root.resolve(round + IN_PROGRESS_SUFFIX);
    }

    /**
     * Deletes an exact in-progress directory and all of its contents.
     *
     * @param round output round number
     * @throws IOException when cleanup fails
     */
    public void deleteInProgress(final int round) throws IOException {
        final Path target = inProgress(round);
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * Atomically publishes an in-progress directory as a completed round.
     *
     * @param round output round number
     * @throws IOException when publication fails or atomic moves are unsupported
     */
    public void publish(final int round) throws IOException {
        final Path source = inProgress(round);
        final Path target = completed(round);
        if (Files.exists(target)) {
            throw new IOException("Completed round already exists: " + target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Filesystem does not support atomic round publication",
                    exception);
        }
    }

    private int parseRound(final String name) {
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void validateRound(final int round) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be positive");
        }
    }
}
