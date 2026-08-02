package cz.coroptis.pegsolitaire;

import org.apache.commons.cli.ParseException;

/**
 * Board implementations available to command-line operations.
 */
public enum BoardVariant {

    ENGLISH("english");

    private final String optionValue;

    BoardVariant(final String optionValue) {
        this.optionValue = optionValue;
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
}
