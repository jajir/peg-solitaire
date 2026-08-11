package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnglishBoardTest {

    private EnglishBoard board;

    @BeforeEach
    void setUp() {
        board = new EnglishBoard();
    }

    @Test
    void englishBoardHasThirtyThreeHolesAndEmptyCenter() {
        assertEquals(33, EnglishBoard.HOLE_COUNT);
        assertEquals(32, Long.bitCount(EnglishBoard.INITIAL_STATE));
        assertEquals(6, board.bitAt(3, 3));
        assertEquals(0L,
                EnglishBoard.INITIAL_STATE & (1L << board.bitAt(3, 3)));
        assertEquals(0x1ffffffbfL, EnglishBoard.INITIAL_STATE);
        assertEquals(0, board.bitAt(0, 2));
        assertEquals(32, board.bitAt(0, 4));
    }

    @Test
    void encodingRoundTripsEveryBoardBit() {
        for (int bit = 0; bit < EnglishBoard.HOLE_COUNT; bit++) {
            final boolean[] expected = new boolean[EnglishBoard.HOLE_COUNT];
            expected[bit] = true;
            assertArrayEquals(expected, board.decode(board.encode(expected)));
        }
    }

    @Test
    void generatesHorizontalJump() {
        assertSingleMove(state(3, 0, 3, 1, 3, 2), state(3, 0, 3, 3));
    }

    @Test
    void generatesVerticalJump() {
        assertSingleMove(state(0, 3, 1, 3, 2, 3), state(0, 3, 3, 3));
    }

    @Test
    void rejectsJumpWithOccupiedDestination() {
        assertEquals(0,
                board.generateSuccessors(
                        state(3, 0, 3, 1, 3, 2, 3, 3, 3, 4, 3, 5, 3, 6),
                        ignored -> {
                        }));
    }

    @Test
    void rejectsJumpWithEmptyOrigin() {
        assertEquals(0, board.generateSuccessors(state(3, 2), ignored -> {
        }));
    }

    @Test
    void rejectsJumpWithEmptyMiddle() {
        assertEquals(0, board.generateSuccessors(state(3, 1), ignored -> {
        }));
    }

    @Test
    void rejectsDiagonalAndOffBoardJumps() {
        assertEquals(0, board.generateSuccessors(state(2, 2, 3, 3), ignored -> {
        }));
        assertEquals(0, board.generateSuccessors(state(0, 2), ignored -> {
        }));
    }

    @Test
    void rejectsBitsOutsideBoard() {
        assertThrows(IllegalArgumentException.class,
                () -> board.decode(1L << EnglishBoard.HOLE_COUNT));
    }

    private void assertSingleMove(final long state, final long expected) {
        final List<Long> successors = new ArrayList<>();
        assertEquals(1, board.generateSuccessors(state, successors::add));
        assertEquals(List.of(expected), successors);
    }

    private long state(final int... coordinates) {
        long state = 0L;
        for (int index = 0; index < coordinates.length; index += 2) {
            state |= 1L << board.bitAt(coordinates[index], coordinates[index + 1]);
        }
        return state;
    }
}
