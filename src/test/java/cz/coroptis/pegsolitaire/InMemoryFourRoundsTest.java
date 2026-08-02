package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class InMemoryFourRoundsTest {

    @Test
    void enumeratesAndPrintsFourRoundsInMemory() {
        final EnglishBoard board = new EnglishBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final int[] expectedUniqueStates = { 1, 1, 2, 8 };
        Set<Long> frontier = Set.of(
                symmetry.canonicalize(EnglishBoard.INITIAL_STATE));

        System.out.println("In-memory peg-solitaire smoke test:");
        for (int round = 1; round <= expectedUniqueStates.length; round++) {
            assertEquals(expectedUniqueStates[round - 1], frontier.size());

            final Set<Long> nextFrontier = new HashSet<>();
            long generatedMoves = 0L;
            for (long state : frontier) {
                generatedMoves += board.generateSuccessors(state,
                        successor -> nextFrontier
                                .add(symmetry.canonicalize(successor)));
            }

            System.out.printf(
                    "  round %d: unique states=%d, generated moves=%d, next unique states=%d%n",
                    round, frontier.size(), generatedMoves,
                    nextFrontier.size());
            frontier = nextFrontier;
        }
    }
}
