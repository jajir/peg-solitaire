package cz.coroptis.pegsolitaire;

import java.nio.file.Path;

/**
 * Shared hardcoded application configuration.
 */
final class ApplicationConfiguration {

    static final Path DATA_ROOT = Path.of("/Volumes/ponrava/peg-solitaire");

    private ApplicationConfiguration() {
    }
}
