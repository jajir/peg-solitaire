package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CliConfigurationTest {

    @Test
    void fullReadyVerificationIsExplicitlyOptIn() {
        assertFalse(new CliConfiguration(CliConfiguration.Action.COUNT,
                BoardVariant.SENKU, Path.of("/tmp/rounds"), 2, 4)
                .verifyReadySummary());
        assertTrue(new CliConfiguration(CliConfiguration.Action.COUNT,
                BoardVariant.SENKU, Path.of("/tmp/rounds"), 2, 4, true)
                .verifyReadySummary());
    }
}
