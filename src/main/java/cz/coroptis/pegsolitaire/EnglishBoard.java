package cz.coroptis.pegsolitaire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Geometry and move rules for the 33-hole English peg-solitaire board.
 */
public final class EnglishBoard {

    public static final int HOLE_COUNT = 33;
    public static final long ALL_PEGS = (1L << HOLE_COUNT) - 1L;
    public static final long INITIAL_STATE = 0x1fffeffffL;

    private static final int BOARD_SIZE = 7;
    private static final int[][] DIRECTIONS = {
            { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }
    };

    private final int[][] bitByCoordinate;
    private final List<Jump> jumps;

    /**
     * Creates the standard English board geometry and its legal jump templates.
     */
    public EnglishBoard() {
        bitByCoordinate = createCoordinateMap();
        jumps = createJumps();
    }

    /**
     * Generates every legal successor of {@code state}.
     *
     * @param state encoded board state
     * @param successorConsumer consumer called once for each legal move
     * @return number of generated moves
     */
    public int generateSuccessors(final long state,
            final LongConsumer successorConsumer) {
        validateState(state);
        if (successorConsumer == null) {
            throw new IllegalArgumentException("successorConsumer must not be null");
        }
        int count = 0;
        for (Jump jump : jumps) {
            if (jump.isLegal(state)) {
                successorConsumer.accept(jump.apply(state));
                count++;
            }
        }
        return count;
    }

    /**
     * Encodes peg occupancy in row-major playable-hole order.
     *
     * @param occupied exactly 33 occupancy flags
     * @return encoded board state
     */
    public long encode(final boolean[] occupied) {
        if (occupied == null || occupied.length != HOLE_COUNT) {
            throw new IllegalArgumentException("occupied must contain exactly 33 values");
        }
        long state = 0L;
        for (int bit = 0; bit < occupied.length; bit++) {
            if (occupied[bit]) {
                state |= 1L << bit;
            }
        }
        return state;
    }

    /**
     * Decodes a state into row-major playable-hole occupancy.
     *
     * @param state encoded board state
     * @return a new 33-element occupancy array
     */
    public boolean[] decode(final long state) {
        validateState(state);
        final boolean[] occupied = new boolean[HOLE_COUNT];
        for (int bit = 0; bit < occupied.length; bit++) {
            occupied[bit] = (state & (1L << bit)) != 0L;
        }
        return occupied;
    }

    int bitAt(final int row, final int column) {
        if (row < 0 || row >= BOARD_SIZE || column < 0
                || column >= BOARD_SIZE) {
            return -1;
        }
        return bitByCoordinate[row][column];
    }

    private int[][] createCoordinateMap() {
        final int[][] map = new int[BOARD_SIZE][BOARD_SIZE];
        for (int[] row : map) {
            Arrays.fill(row, -1);
        }
        int bit = 0;
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int column = 0; column < BOARD_SIZE; column++) {
                if (isPlayable(row, column)) {
                    map[row][column] = bit++;
                }
            }
        }
        if (bit != HOLE_COUNT) {
            throw new IllegalStateException("English board must contain 33 holes");
        }
        return map;
    }

    private List<Jump> createJumps() {
        final List<Jump> result = new ArrayList<>();
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int column = 0; column < BOARD_SIZE; column++) {
                final int from = bitAt(row, column);
                if (from < 0) {
                    continue;
                }
                for (int[] direction : DIRECTIONS) {
                    final int over = bitAt(row + direction[0],
                            column + direction[1]);
                    final int to = bitAt(row + 2 * direction[0],
                            column + 2 * direction[1]);
                    if (over >= 0 && to >= 0) {
                        result.add(new Jump(from, over, to));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean isPlayable(final int row, final int column) {
        return (row >= 2 && row <= 4) || (column >= 2 && column <= 4);
    }

    private void validateState(final long state) {
        if ((state & ~ALL_PEGS) != 0L) {
            throw new IllegalArgumentException("state contains bits outside the board");
        }
    }

    private static final class Jump {

        private final long occupiedMask;
        private final long destinationMask;
        private final long changedMask;

        private Jump(final int from, final int over, final int to) {
            occupiedMask = (1L << from) | (1L << over);
            destinationMask = 1L << to;
            changedMask = occupiedMask | destinationMask;
        }

        private boolean isLegal(final long state) {
            return (state & occupiedMask) == occupiedMask
                    && (state & destinationMask) == 0L;
        }

        private long apply(final long state) {
            return state ^ changedMask;
        }
    }
}
