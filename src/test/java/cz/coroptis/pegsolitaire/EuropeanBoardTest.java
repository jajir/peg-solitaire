package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EuropeanBoardTest {

    private EuropeanBoard board;
    private BoardSymmetry symmetry;

    @BeforeEach
    void setUp() {
        board = new EuropeanBoard();
        symmetry = new BoardSymmetry(board);
    }

    @Test
    void europeanBoardHasThirtySevenHolesAndEmptyCenter() {
        assertEquals(37, board.holeCount());
        assertEquals(36, Long.bitCount(board.initialState()));
        assertEquals(7, board.bitAt(3, 3));
        assertEquals(0L, board.initialState() & (1L << board.bitAt(3, 3)));
        assertEquals(0x1fffffff7fL, board.initialState());
        assertEquals(0, board.bitAt(1, 1));
        assertEquals(36, board.bitAt(0, 4));
    }

    @Test
    void europeanBoardAddsFourShoulderHoles() {
        assertTrue(board.bitAt(1, 1) >= 0);
        assertTrue(board.bitAt(1, 5) >= 0);
        assertTrue(board.bitAt(5, 1) >= 0);
        assertTrue(board.bitAt(5, 5) >= 0);
        assertEquals(-1, board.bitAt(0, 1));
        assertEquals(-1, board.bitAt(6, 5));
    }

    @Test
    void encodingRoundTripsAllThirtySevenBits() {
        for (int bit = 0; bit < board.holeCount(); bit++) {
            final boolean[] expected = new boolean[board.holeCount()];
            expected[bit] = true;
            assertArrayEquals(expected, board.decode(board.encode(expected)));
        }
    }

    @Test
    void firstSevenCanonicalFrontiersHaveExpectedSizes() {
        final int[] expectedSizes = { 1, 1, 3, 15, 70, 341, 1604 };
        Set<Long> frontier = Set.of(symmetry.canonicalize(board.initialState()));

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
}
