package cz.coroptis.pegsolitaire;

import java.nio.file.Path;

/**
 * Validated command-line configuration used by application commands.
 */
final class CliConfiguration {

    enum Action {
        COUNT,
        STATS
    }

    private final Action action;
    private final BoardVariant board;
    private final Path directory;
    private final int workerCount;
    private final int queueCapacity;

    CliConfiguration(final Action action, final BoardVariant board,
            final Path directory, final int workerCount,
            final int queueCapacity) {
        this.action = action;
        this.board = board;
        this.directory = directory;
        this.workerCount = workerCount;
        this.queueCapacity = queueCapacity;
    }

    Action action() {
        return action;
    }

    BoardVariant board() {
        return board;
    }

    Path directory() {
        return directory;
    }

    int workerCount() {
        return workerCount;
    }

    int queueCapacity() {
        return queueCapacity;
    }
}
