package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoardSymmetryTest {

    private EnglishBoard board;
    private BoardSymmetry symmetry;

    @BeforeEach
    void setUp() {
        board = new EnglishBoard();
        symmetry = new BoardSymmetry(board);
    }

    @Test
    void allTransformsHaveOneCanonicalRepresentative() {
        final long asymmetric = state(0, 2, 1, 2, 2, 3, 4, 6);
        final long canonical = symmetry.canonicalize(asymmetric);

        for (int transform = 0; transform < 8; transform++) {
            assertEquals(canonical,
                    symmetry.canonicalize(symmetry.transform(asymmetric, transform)));
        }
    }

    @Test
    void canonicalizationIsIdempotent() {
        final long canonical = symmetry.canonicalize(state(0, 2, 2, 4, 5, 3));
        assertEquals(canonical, symmetry.canonicalize(canonical));
    }

    @Test
    void fourSymmetricOpeningMovesCollapseToOneState() {
        final Set<Long> successors = new HashSet<>();
        assertEquals(4, board.generateSuccessors(EnglishBoard.INITIAL_STATE,
                state -> successors.add(symmetry.canonicalize(state))));
        assertEquals(1, successors.size());
    }

    @Test
    void firstFiveCanonicalFrontiersHaveExpectedSizes() {
        Set<Long> frontier = Set.of(
                symmetry.canonicalize(EnglishBoard.INITIAL_STATE));
        final int[] expectedSizes = { 1, 1, 2, 8, 39 };

        for (int expectedSize : expectedSizes) {
            assertEquals(expectedSize, frontier.size());
            final Set<Long> next = new HashSet<>();
            for (long state : frontier) {
                board.generateSuccessors(state,
                        successor -> next.add(symmetry.canonicalize(successor)));
            }
            frontier = next;
        }
    }

    private long state(final int... coordinates) {
        long state = 0L;
        for (int index = 0; index < coordinates.length; index += 2) {
            state |= 1L << board.bitAt(coordinates[index], coordinates[index + 1]);
        }
        return state;
    }
}
