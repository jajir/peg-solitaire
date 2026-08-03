package cz.coroptis.pegsolitaire;

import java.util.function.LongConsumer;

/**
 * Geometry, encoding, initial state, and move rules for one board variant.
 */
public interface PegSolitaireBoard {

    int boardSize();

    int holeCount();

    long allPegs();

    long initialState();

    int generateSuccessors(long state, LongConsumer successorConsumer);

    long encode(boolean[] occupied);

    boolean[] decode(long state);

    int bitAt(int row, int column);
}
