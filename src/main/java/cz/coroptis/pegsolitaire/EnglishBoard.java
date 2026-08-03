package cz.coroptis.pegsolitaire;

/**
 * Geometry and move rules for the 33-hole English peg-solitaire board.
 */
public final class EnglishBoard extends AbstractCrossBoard {

    public static final int HOLE_COUNT = 33;
    public static final long ALL_PEGS = (1L << HOLE_COUNT) - 1L;
    public static final long INITIAL_STATE = 0x1fffeffffL;

    private static final int[] MINIMUM_COLUMN_BY_ROW = { 2, 2, 0, 0, 0, 2, 2 };
    private static final int[] MAXIMUM_COLUMN_BY_ROW = { 4, 4, 6, 6, 6, 4, 4 };

    /**
     * Creates the standard center-empty English board.
     */
    public EnglishBoard() {
        super("English", HOLE_COUNT, MINIMUM_COLUMN_BY_ROW,
                MAXIMUM_COLUMN_BY_ROW, 3, 3);
        if (initialState() != INITIAL_STATE) {
            throw new IllegalStateException("Unexpected English board encoding");
        }
    }
}
