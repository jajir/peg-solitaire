package cz.coroptis.pegsolitaire;

/**
 * Immutable statistics from one application invocation.
 */
public final class RoundResult {

    private final int sourceRound;
    private final int destinationRound;
    private final long processedStates;
    private final long generatedMoves;
    private final long uniqueStates;
    private final boolean initialized;
    private final boolean terminal;

    private RoundResult(final int sourceRound, final int destinationRound,
            final long processedStates, final long generatedMoves,
            final long uniqueStates, final boolean initialized,
            final boolean terminal) {
        this.sourceRound = sourceRound;
        this.destinationRound = destinationRound;
        this.processedStates = processedStates;
        this.generatedMoves = generatedMoves;
        this.uniqueStates = uniqueStates;
        this.initialized = initialized;
        this.terminal = terminal;
    }

    public static RoundResult initialized() {
        return new RoundResult(0, 1, 0L, 0L, 1L, true, false);
    }

    public static RoundResult counted(final int sourceRound,
            final long processedStates, final long generatedMoves,
            final long uniqueStates) {
        return new RoundResult(sourceRound, sourceRound + 1, processedStates,
                generatedMoves, uniqueStates, false, false);
    }

    public static RoundResult terminal(final int sourceRound) {
        return new RoundResult(sourceRound, 0, 0L, 0L, 0L, false, true);
    }

    public int sourceRound() {
        return sourceRound;
    }

    public int destinationRound() {
        return destinationRound;
    }

    public long processedStates() {
        return processedStates;
    }

    public long generatedMoves() {
        return generatedMoves;
    }

    public long uniqueStates() {
        return uniqueStates;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
