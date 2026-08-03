package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SenkuBoardTest {

    private SenkuBoard board;
    private BoardSymmetry symmetry;

    @BeforeEach
    void setUp() {
        board = new SenkuBoard();
        symmetry = new BoardSymmetry(board);
    }

    @Test
    void senkuBoardMatchesTheFortyNineHoleLayout() {
        assertEquals(9, board.boardSize());
        assertEquals(49, board.holeCount());
        assertEquals(48, Long.bitCount(board.initialState()));
        assertEquals(24, board.bitAt(4, 4));
        assertEquals(SenkuBoard.INITIAL_STATE, board.initialState());

        final String[] layout = {
                "...###...", "...###...", "..#####..",
                "#########", "#########", "#########",
                "..#####..", "...###...", "...###..."
        };
        for (int row = 0; row < board.boardSize(); row++) {
            for (int column = 0; column < board.boardSize(); column++) {
                assertEquals(layout[row].charAt(column) == '#',
                        board.bitAt(row, column) >= 0);
            }
        }
    }

    @Test
    void fourSymmetricOpeningMovesCollapseToOneState() {
        final Set<Long> successors = new HashSet<>();
        assertEquals(4, board.generateSuccessors(board.initialState(),
                state -> successors.add(symmetry.canonicalize(state))));
        assertEquals(1, successors.size());
    }
}
