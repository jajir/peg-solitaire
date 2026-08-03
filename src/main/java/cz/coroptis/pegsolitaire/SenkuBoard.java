package cz.coroptis.pegsolitaire;

/**
 * Geometry and move rules for the center-empty 49-hole Senku board.
 */
public final class SenkuBoard extends AbstractCrossBoard {

    public static final int HOLE_COUNT = 49;
    public static final long ALL_PEGS = (1L << HOLE_COUNT) - 1L;
    public static final long INITIAL_STATE = 0x1fffffeffffffL;

    private static final int[] MINIMUM_COLUMN_BY_ROW = {
            3, 3, 2, 0, 0, 0, 2, 3, 3
    };
    private static final int[] MAXIMUM_COLUMN_BY_ROW = {
            5, 5, 6, 8, 8, 8, 6, 5, 5
    };

    /**
     * Creates the center-empty Senku board.
     */
    public SenkuBoard() {
        super("Senku", HOLE_COUNT, MINIMUM_COLUMN_BY_ROW,
                MAXIMUM_COLUMN_BY_ROW, 4, 4);
        if (initialState() != INITIAL_STATE) {
            throw new IllegalStateException("Unexpected Senku board encoding");
        }
    }
}
