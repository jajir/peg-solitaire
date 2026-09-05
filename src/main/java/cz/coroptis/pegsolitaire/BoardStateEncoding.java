package cz.coroptis.pegsolitaire;

import java.util.Objects;

import org.hestiastore.index.chunkentryfile.KeyPageCodec;
import org.hestiastore.index.chunkentryfile.KeyPageCodecs;

/**
 * Describes fixed-population board pages using four conserved binary parities.
 * Each orthogonal jump visits all three colors in both diagonal
 * three-colorings, so the parity of the holes outside any one color is
 * unchanged. These masks use the board's actual Hilbert bit numbering, not a
 * separate stored layout.
 */
final class BoardStateEncoding {

    private final int bitCount;
    private final long[] parityMasks = new long[4];
    private final int initialSyndrome;

    BoardStateEncoding(final PegSolitaireBoard board) {
        Objects.requireNonNull(board, "board");
        bitCount = board.holeCount();
        for (int row = 0; row < board.boardSize(); row++) {
            for (int column = 0; column < board.boardSize(); column++) {
                final int bit = board.bitAt(row, column);
                if (bit < 0) {
                    continue;
                }
                final int firstColor = (row + column) % 3;
                final int secondColor = Math.floorMod(row - column, 3);
                for (int color = 0; color < 2; color++) {
                    if (firstColor != color) {
                        parityMasks[color] |= 1L << bit;
                    }
                    if (secondColor != color) {
                        parityMasks[color + 2] |= 1L << bit;
                    }
                }
            }
        }
        initialSyndrome = syndrome(board.initialState());
    }

    /**
     * Selects the initial-position parity class for one reachable population.
     * Zero/one-peg pages use ordinary deltas: some boards have no keys of that
     * population in their initial class, but an empty terminal index is valid.
     */
    KeyPageCodec<Long> codecForPopulation(final int population) {
        validatePopulation(population);
        if (population <= 1) {
            return KeyPageCodecs.longDeltaVarint();
        }
        return KeyPageCodecs.longFixedWeightDeltaVarint(bitCount, population,
                parityMasks, initialSyndrome);
    }

    /**
     * Determines the next population from source keys rather than directory
     * numbering. Manually supplied frontiers outside the initial parity class
     * retain fixed-weight compression without parity restrictions. A mixed
     * population sample is not a breadth-first frontier and fails before
     * writes.
     */
    KeyPageCodec<Long> codecForSuccessors(final RoundStateSample source) {
        Objects.requireNonNull(source, "source");
        final long[] states = source.states();
        if (source.stateBitCount() != bitCount || states.length == 0) {
            throw new IllegalArgumentException(
                    "Source sample has no matching board states");
        }
        final int population = Long.bitCount(states[0]);
        boolean initialClass = true;
        for (final long state : states) {
            if (Long.bitCount(state) != population) {
                throw new IllegalArgumentException(
                        "Source frontier mixes peg populations");
            }
            initialClass &= syndrome(state) == initialSyndrome;
        }
        final int nextPopulation = Math.max(0, population - 1);
        if (initialClass || nextPopulation <= 1) {
            return codecForPopulation(nextPopulation);
        }
        return KeyPageCodecs.longFixedWeightDeltaVarint(bitCount,
                nextPopulation, new long[0], 0);
    }

    private int syndrome(final long state) {
        int result = 0;
        for (int index = 0; index < parityMasks.length; index++) {
            result |= (Long.bitCount(state & parityMasks[index]) & 1) << index;
        }
        return result;
    }

    private void validatePopulation(final int population) {
        if (population < 0 || population > bitCount) {
            throw new IllegalArgumentException(
                    "Population is outside board bounds");
        }
    }
}
