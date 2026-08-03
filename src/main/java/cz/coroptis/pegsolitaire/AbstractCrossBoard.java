package cz.coroptis.pegsolitaire;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Shared row-major encoding and orthogonal jump engine for symmetric 7x7 cross
 * boards.
 */
abstract class AbstractCrossBoard implements PegSolitaireBoard {

    private static final int BOARD_SIZE = 7;
    private static final int[][] DIRECTIONS = {
            { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }
    };

    private final String boardName;
    private final int holeCount;
    private final long allPegs;
    private final long initialState;
    private final int[][] bitByCoordinate;
    private final List<Jump> jumps;

    AbstractCrossBoard(final String boardName, final int expectedHoleCount,
            final int[] minimumColumnByRow,
            final int[] maximumColumnByRow, final int emptyRow,
            final int emptyColumn) {
        this.boardName = boardName;
        validateRowBounds(minimumColumnByRow, maximumColumnByRow);
        bitByCoordinate = createCoordinateMap(minimumColumnByRow,
                maximumColumnByRow);
        holeCount = countHoles();
        if (holeCount != expectedHoleCount) {
            throw new IllegalStateException(boardName + " board must contain "
                    + expectedHoleCount + " holes");
        }
        allPegs = (1L << holeCount) - 1L;
        final int emptyBit = bitAt(emptyRow, emptyColumn);
        if (emptyBit < 0) {
            throw new IllegalArgumentException(
                    "Initial empty coordinate is not playable");
        }
        initialState = allPegs ^ (1L << emptyBit);
        jumps = createJumps();
    }

    @Override
    public final int holeCount() {
        return holeCount;
    }

    @Override
    public final long allPegs() {
        return allPegs;
    }

    @Override
    public final long initialState() {
        return initialState;
    }

    @Override
    public final int generateSuccessors(final long state,
            final LongConsumer successorConsumer) {
        validateState(state);
        if (successorConsumer == null) {
            throw new IllegalArgumentException(
                    "successorConsumer must not be null");
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

    @Override
    public final long encode(final boolean[] occupied) {
        if (occupied == null || occupied.length != holeCount) {
            throw new IllegalArgumentException("occupied must contain exactly "
                    + holeCount + " values");
        }
        long state = 0L;
        for (int bit = 0; bit < occupied.length; bit++) {
            if (occupied[bit]) {
                state |= 1L << bit;
            }
        }
        return state;
    }

    @Override
    public final boolean[] decode(final long state) {
        validateState(state);
        final boolean[] occupied = new boolean[holeCount];
        for (int bit = 0; bit < occupied.length; bit++) {
            occupied[bit] = (state & (1L << bit)) != 0L;
        }
        return occupied;
    }

    @Override
    public final int bitAt(final int row, final int column) {
        if (row < 0 || row >= BOARD_SIZE || column < 0
                || column >= BOARD_SIZE) {
            return -1;
        }
        return bitByCoordinate[row][column];
    }

    private void validateRowBounds(final int[] minimumColumnByRow,
            final int[] maximumColumnByRow) {
        if (minimumColumnByRow == null || maximumColumnByRow == null
                || minimumColumnByRow.length != BOARD_SIZE
                || maximumColumnByRow.length != BOARD_SIZE) {
            throw new IllegalArgumentException(
                    "Board row bounds must contain exactly seven rows");
        }
    }

    private int[][] createCoordinateMap(final int[] minimumColumnByRow,
            final int[] maximumColumnByRow) {
        final int[][] map = new int[BOARD_SIZE][BOARD_SIZE];
        for (int[] row : map) {
            Arrays.fill(row, -1);
        }
        int bit = 0;
        for (int row = 0; row < BOARD_SIZE; row++) {
            final int minimum = minimumColumnByRow[row];
            final int maximum = maximumColumnByRow[row];
            if (minimum < 0 || maximum >= BOARD_SIZE || minimum > maximum) {
                throw new IllegalArgumentException(
                        "Invalid playable columns for row " + row);
            }
            for (int column = minimum; column <= maximum; column++) {
                map[row][column] = bit++;
            }
        }
        return map;
    }

    private int countHoles() {
        int count = 0;
        for (int[] row : bitByCoordinate) {
            for (int bit : row) {
                if (bit >= 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<Jump> createJumps() {
        final List<Jump> result = new ArrayList<>();
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int column = 0; column < BOARD_SIZE; column++) {
                final int from = bitAt(row, column);
                if (from >= 0) {
                    addJumpsFrom(result, row, column, from);
                }
            }
        }
        return List.copyOf(result);
    }

    private void addJumpsFrom(final List<Jump> result, final int row,
            final int column, final int from) {
        for (int[] direction : DIRECTIONS) {
            final int over = bitAt(row + direction[0], column + direction[1]);
            final int to = bitAt(row + 2 * direction[0],
                    column + 2 * direction[1]);
            if (over >= 0 && to >= 0) {
                result.add(new Jump(from, over, to));
            }
        }
    }

    private void validateState(final long state) {
        if ((state & ~allPegs) != 0L) {
            throw new IllegalArgumentException(
                    "state contains bits outside the " + boardName + " board");
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
