package cz.coroptis.pegsolitaire;

import java.util.function.Supplier;

import org.apache.commons.cli.ParseException;

/**
 * Board implementations available to command-line operations.
 */
public enum BoardVariant {

    ENGLISH("english", EnglishBoard::new),
    EUROPEAN("european", EuropeanBoard::new);

    private final String optionValue;
    private final Supplier<PegSolitaireBoard> boardSupplier;

    BoardVariant(final String optionValue,
            final Supplier<PegSolitaireBoard> boardSupplier) {
        this.optionValue = optionValue;
        this.boardSupplier = boardSupplier;
    }

    /**
     * Resolves a case-insensitive command-line board name.
     *
     * @param value command-line value
     * @return matching board variant
     * @throws ParseException when the board is unsupported
     */
    public static BoardVariant parse(final String value) throws ParseException {
        for (BoardVariant variant : values()) {
            if (variant.optionValue.equalsIgnoreCase(value)) {
                return variant;
            }
        }
        throw new ParseException("Unsupported board: " + value);
    }

    public String optionValue() {
        return optionValue;
    }

    /**
     * Creates a new board engine for this variant.
     *
     * @return selected board implementation
     */
    public PegSolitaireBoard createBoard() {
        return boardSupplier.get();
    }
}
