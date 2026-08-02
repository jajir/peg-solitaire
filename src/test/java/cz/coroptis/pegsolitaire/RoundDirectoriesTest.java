package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RoundDirectoriesTest {

    @TempDir
    private Path temporaryDirectory;

    @Test
    void findsHighestNumericDirectoryAndIgnoresOtherEntries() throws IOException {
        final RoundDirectories directories = new RoundDirectories(temporaryDirectory);
        Files.createDirectories(temporaryDirectory.resolve("1"));
        Files.createDirectories(temporaryDirectory.resolve("7"));
        Files.createDirectories(temporaryDirectory.resolve("8.in-progress"));
        Files.createDirectories(temporaryDirectory.resolve("not-a-round"));
        Files.createDirectories(temporaryDirectory.resolve("999999999999999999"));
        Files.writeString(temporaryDirectory.resolve("9"), "not a directory");

        assertEquals(7, directories.latestCompletedRound().orElseThrow());
        assertEquals(List.of(1, 7), directories.completedRounds());
    }

    @Test
    void deletesOnlyRequestedInProgressDirectory() throws IOException {
        final RoundDirectories directories = new RoundDirectories(temporaryDirectory);
        final Path incomplete = directories.inProgress(2);
        Files.createDirectories(incomplete.resolve("nested"));
        Files.writeString(incomplete.resolve("nested/data"), "temporary");
        Files.createDirectories(directories.completed(1));

        directories.deleteInProgress(2);

        assertFalse(Files.exists(incomplete));
        assertTrue(Files.isDirectory(directories.completed(1)));
    }

    @Test
    void publishesTemporaryRound() throws IOException {
        final RoundDirectories directories = new RoundDirectories(temporaryDirectory);
        Files.createDirectories(directories.inProgress(3));

        directories.publish(3);

        assertTrue(Files.isDirectory(directories.completed(3)));
        assertFalse(Files.exists(directories.inProgress(3)));
    }
}
