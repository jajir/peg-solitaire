package cz.coroptis.pegsolitaire;

/**
 * Command-line entry point for completed-round statistics.
 */
public final class PegSolitaireStatsMain {

    private PegSolitaireStatsMain() {
    }

    /**
     * Prints statistics using the hardcoded data root.
     *
     * @param args must be empty
     */
    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("This application accepts no arguments.");
            System.exit(2);
        }
        try {
            new RoundStatisticsReporter(ApplicationConfiguration.DATA_ROOT)
                    .print(System.out);
        } catch (Exception exception) {
            System.err.println("Statistics failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
