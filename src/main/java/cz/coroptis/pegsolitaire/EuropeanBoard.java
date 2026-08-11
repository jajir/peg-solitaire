package cz.coroptis.pegsolitaire;

/**
 * Geometry and move rules for the center-empty 37-hole European/French board.
 */
public final class EuropeanBoard extends AbstractCrossBoard {

    public static final int HOLE_COUNT = 37;
    public static final long ALL_PEGS = (1L << HOLE_COUNT) - 1L;
    public static final long INITIAL_STATE = 0x1fffffff7fL;

    private static final int[] MINIMUM_COLUMN_BY_ROW = { 2, 1, 0, 0, 0, 1, 2 };
    private static final int[] MAXIMUM_COLUMN_BY_ROW = { 4, 5, 6, 6, 6, 5, 4 };

    /**
     * Creates the traditional center-empty European board.
     */
    public EuropeanBoard() {
        super("European", HOLE_COUNT, MINIMUM_COLUMN_BY_ROW,
                MAXIMUM_COLUMN_BY_ROW, 3, 3);
        if (initialState() != INITIAL_STATE) {
            throw new IllegalStateException("Unexpected European board encoding");
        }
    }
}
