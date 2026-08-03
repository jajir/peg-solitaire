package cz.coroptis.pegsolitaire;

/**
 * Canonicalizes square-board states across the eight rotations and reflections.
 */
public final class BoardSymmetry {

    private static final int TRANSFORM_COUNT = 8;

    private final PegSolitaireBoard board;
    private final int boardMax;
    private final int[][] transformedBits;

    /**
     * Builds symmetry mappings for the supplied board geometry.
     *
     * @param board board geometry
     */
    public BoardSymmetry(final PegSolitaireBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board must not be null");
        }
        this.board = board;
        boardMax = board.boardSize() - 1;
        transformedBits = new int[TRANSFORM_COUNT][board.holeCount()];
        for (int row = 0; row <= boardMax; row++) {
            for (int column = 0; column <= boardMax; column++) {
                final int sourceBit = board.bitAt(row, column);
                if (sourceBit >= 0) {
                    mapTransforms(board, row, column, sourceBit);
                }
            }
        }
    }

    /**
     * Returns the numerically smallest representative of a state's symmetry
     * class.
     *
     * @param state encoded board state
     * @return canonical state
     */
    public long canonicalize(final long state) {
        if ((state & ~board.allPegs()) != 0L) {
            throw new IllegalArgumentException("state contains bits outside the board");
        }
        long canonical = Long.MAX_VALUE;
        for (int transform = 0; transform < TRANSFORM_COUNT; transform++) {
            canonical = Math.min(canonical, transform(state, transform));
        }
        return canonical;
    }

    long transform(final long state, final int transform) {
        if (transform < 0 || transform >= TRANSFORM_COUNT) {
            throw new IllegalArgumentException("transform must be between 0 and 7");
        }
        long result = 0L;
        for (int sourceBit = 0; sourceBit < board.holeCount();
                sourceBit++) {
            if ((state & (1L << sourceBit)) != 0L) {
                result |= 1L << transformedBits[transform][sourceBit];
            }
        }
        return result;
    }

    private void mapTransforms(final PegSolitaireBoard board, final int row,
            final int column, final int sourceBit) {
        transformedBits[0][sourceBit] = board.bitAt(row, column);
        transformedBits[1][sourceBit] = board.bitAt(column, boardMax - row);
        transformedBits[2][sourceBit] = board.bitAt(boardMax - row,
                boardMax - column);
        transformedBits[3][sourceBit] = board.bitAt(boardMax - column, row);
        transformedBits[4][sourceBit] = board.bitAt(row, boardMax - column);
        transformedBits[5][sourceBit] = board.bitAt(boardMax - row, column);
        transformedBits[6][sourceBit] = board.bitAt(column, row);
        transformedBits[7][sourceBit] = board.bitAt(boardMax - column,
                boardMax - row);
    }
}
