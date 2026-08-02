package cz.coroptis.pegsolitaire;

/**
 * Canonicalizes English-board states across the eight square symmetries.
 */
public final class BoardSymmetry {

    private static final int TRANSFORM_COUNT = 8;
    private static final int BOARD_MAX = 6;

    private final int[][] transformedBits;

    /**
     * Builds symmetry mappings for the supplied English-board geometry.
     *
     * @param board board geometry
     */
    public BoardSymmetry(final EnglishBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board must not be null");
        }
        transformedBits = new int[TRANSFORM_COUNT][EnglishBoard.HOLE_COUNT];
        for (int row = 0; row <= BOARD_MAX; row++) {
            for (int column = 0; column <= BOARD_MAX; column++) {
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
        if ((state & ~EnglishBoard.ALL_PEGS) != 0L) {
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
        for (int sourceBit = 0; sourceBit < EnglishBoard.HOLE_COUNT;
                sourceBit++) {
            if ((state & (1L << sourceBit)) != 0L) {
                result |= 1L << transformedBits[transform][sourceBit];
            }
        }
        return result;
    }

    private void mapTransforms(final EnglishBoard board, final int row,
            final int column, final int sourceBit) {
        transformedBits[0][sourceBit] = board.bitAt(row, column);
        transformedBits[1][sourceBit] = board.bitAt(column, BOARD_MAX - row);
        transformedBits[2][sourceBit] = board.bitAt(BOARD_MAX - row,
                BOARD_MAX - column);
        transformedBits[3][sourceBit] = board.bitAt(BOARD_MAX - column, row);
        transformedBits[4][sourceBit] = board.bitAt(row, BOARD_MAX - column);
        transformedBits[5][sourceBit] = board.bitAt(BOARD_MAX - row, column);
        transformedBits[6][sourceBit] = board.bitAt(column, row);
        transformedBits[7][sourceBit] = board.bitAt(BOARD_MAX - column,
                BOARD_MAX - row);
    }
}
