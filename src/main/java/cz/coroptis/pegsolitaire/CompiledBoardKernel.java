package cz.coroptis.pegsolitaire;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * Immutable compiled move and symmetry tables for square orthogonal-jump
 * boards. States entering and leaving this kernel retain the board's original
 * encoding; the two-word row-major representation exists only while finding
 * legal jumps.
 */
final class CompiledBoardKernel {

    private static final int[][] DIRECTIONS = { { -1, 0 }, { 1, 0 }, { 0, -1 },
            { 0, 1 } };
    private static final int BYTE_VALUES = 256;

    private final long allPegs;
    private final int byteCount;
    private final int[] rawDeltas;
    private final long[][][] parentTransforms;
    private final long[][] rawLow;
    private final long[][] rawHigh;
    private final long[] validLow;
    private final long[] validHigh;
    private final int[][] rawJump;
    private final long[][] transformedJumpMasks;

    /**
     * Compiles independent immutable tables for the supplied board and
     * symmetry.
     */
    static CompiledBoardKernel create(final PegSolitaireBoard board,
            final BoardSymmetry symmetry) {
        if (board == null || symmetry == null) {
            throw new IllegalArgumentException(
                    "board and symmetry must not be null");
        }
        return new CompiledBoardKernel(board, symmetry);
    }

    private CompiledBoardKernel(final PegSolitaireBoard board,
            final BoardSymmetry symmetry) {
        final int boardSize = board.boardSize();
        final int holeCount = board.holeCount();
        if (boardSize < 1 || boardSize > 11 || holeCount < 1 || holeCount > 63
                || board.allPegs() != (1L << holeCount) - 1L) {
            throw new IllegalArgumentException(
                    "board must fit two row-major words and 63 encoded bits");
        }
        allPegs = board.allPegs();
        byteCount = (holeCount + 7) / 8;
        rawDeltas = new int[] { -boardSize, boardSize, -1, 1 };
        parentTransforms = new long[BoardSymmetry.TRANSFORM_COUNT][byteCount][BYTE_VALUES];
        rawLow = new long[byteCount][BYTE_VALUES];
        rawHigh = new long[byteCount][BYTE_VALUES];
        validLow = new long[DIRECTIONS.length];
        validHigh = new long[DIRECTIONS.length];
        rawJump = new int[DIRECTIONS.length][boardSize * boardSize];
        final List<long[]> jumpMasks = new ArrayList<>();
        long seenBits = 0L;
        for (int row = 0; row < boardSize; row++) {
            for (int column = 0; column < boardSize; column++) {
                final int bit = board.bitAt(row, column);
                if (bit < 0) {
                    continue;
                }
                if (bit >= holeCount || (seenBits & (1L << bit)) != 0L) {
                    throw new IllegalArgumentException(
                            "board coordinates must uniquely map encoded bits");
                }
                seenBits |= 1L << bit;
                validateSymmetry(board, symmetry, row, column, bit);
                final int rawBit = row * boardSize + column;
                compileRawConversion(bit, rawBit);
                compileJumps(board, symmetry, row, column, bit, rawBit,
                        jumpMasks);
            }
        }
        if (seenBits != allPegs) {
            throw new IllegalArgumentException(
                    "board coordinates must cover every encoded bit");
        }
        transformedJumpMasks = jumpMasks.toArray(long[][]::new);
        compileParentTransforms(symmetry);
    }

    /**
     * Emits the canonical child for every legal directed jump, including equal
     * children reached through different jumps. The caller must supply an
     * eight-long scratch array that is not shared with another active call.
     *
     * @return number of emitted children, before any deduplication
     */
    int generateCanonicalSuccessors(final long state,
            final long[] transformedParentScratch,
            final LongConsumer successorConsumer) {
        if ((state & ~allPegs) != 0L) {
            throw new IllegalArgumentException(
                    "state contains bits outside the board");
        }
        if (transformedParentScratch == null
                || transformedParentScratch.length != BoardSymmetry.TRANSFORM_COUNT) {
            throw new IllegalArgumentException(
                    "transformedParentScratch must contain exactly eight values");
        }
        if (successorConsumer == null) {
            throw new IllegalArgumentException(
                    "successorConsumer must not be null");
        }
        transformParent(state, transformedParentScratch);
        long low = 0L;
        long high = 0L;
        for (int part = 0; part < byteCount; part++) {
            final int value = (int) (state >>> (part * 8)) & 255;
            low |= rawLow[part][value];
            high |= rawHigh[part][value];
        }
        int count = 0;
        for (int direction = 0; direction < DIRECTIONS.length; direction++) {
            final int delta = rawDeltas[direction];
            final int shift = Math.abs(delta);
            final int twice = shift * 2;
            final long lowOne;
            final long lowTwo;
            final long highOne;
            final long highTwo;
            if (delta > 0) {
                lowOne = (low >>> shift) | (high << (64 - shift));
                lowTwo = (low >>> twice) | (high << (64 - twice));
                highOne = high >>> shift;
                highTwo = high >>> twice;
            } else {
                lowOne = low << shift;
                lowTwo = low << twice;
                highOne = (high << shift) | (low >>> (64 - shift));
                highTwo = (high << twice) | (low >>> (64 - twice));
            }
            // Geometry masks also prevent horizontal wrapping between rows.
            long legalLow = low & lowOne & ~lowTwo & validLow[direction];
            long legalHigh = high & highOne & ~highTwo & validHigh[direction];
            while (legalLow != 0L) {
                final int rawBit = Long.numberOfTrailingZeros(legalLow);
                successorConsumer.accept(canonicalChild(
                        transformedParentScratch, rawJump[direction][rawBit]));
                count++;
                legalLow &= legalLow - 1L;
            }
            while (legalHigh != 0L) {
                final int rawBit = 64 + Long.numberOfTrailingZeros(legalHigh);
                successorConsumer.accept(canonicalChild(
                        transformedParentScratch, rawJump[direction][rawBit]));
                count++;
                legalHigh &= legalHigh - 1L;
            }
        }
        return count;
    }

    private void transformParent(final long state, final long[] destination) {
        destination[0] = state;
        for (int transform = 1; transform < BoardSymmetry.TRANSFORM_COUNT; transform++) {
            final long[][] lookup = parentTransforms[transform];
            long transformed = 0L;
            for (int part = 0; part < byteCount; part++) {
                transformed |= lookup[part][(int) (state >>> (part * 8)) & 255];
            }
            destination[transform] = transformed;
        }
    }

    private long canonicalChild(final long[] transformedParent,
            final int jump) {
        final long[] masks = transformedJumpMasks[jump];
        long canonical = transformedParent[0] ^ masks[0];
        for (int transform = 1; transform < BoardSymmetry.TRANSFORM_COUNT; transform++) {
            canonical = Math.min(canonical,
                    transformedParent[transform] ^ masks[transform]);
        }
        return canonical;
    }

    private void compileRawConversion(final int bit, final int rawBit) {
        final int part = bit / 8;
        final int bitInByte = 1 << (bit % 8);
        for (int value = 0; value < BYTE_VALUES; value++) {
            if ((value & bitInByte) != 0) {
                if (rawBit < 64) {
                    rawLow[part][value] |= 1L << rawBit;
                } else {
                    rawHigh[part][value] |= 1L << (rawBit - 64);
                }
            }
        }
    }

    private void compileJumps(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final int row, final int column,
            final int bit, final int rawBit, final List<long[]> jumpMasks) {
        for (int direction = 0; direction < DIRECTIONS.length; direction++) {
            final int[] step = DIRECTIONS[direction];
            final int over = board.bitAt(row + step[0], column + step[1]);
            final int to = board.bitAt(row + 2 * step[0], column + 2 * step[1]);
            if (over < 0 || to < 0) {
                continue;
            }
            if (rawBit < 64) {
                validLow[direction] |= 1L << rawBit;
            } else {
                validHigh[direction] |= 1L << (rawBit - 64);
            }
            rawJump[direction][rawBit] = jumpMasks.size();
            final long[] masks = new long[BoardSymmetry.TRANSFORM_COUNT];
            symmetry.transformAll((1L << bit) | (1L << over) | (1L << to),
                    masks);
            jumpMasks.add(masks);
        }
    }

    private void compileParentTransforms(final BoardSymmetry symmetry) {
        for (int transform = 1; transform < BoardSymmetry.TRANSFORM_COUNT; transform++) {
            for (int part = 0; part < byteCount; part++) {
                for (int value = 0; value < BYTE_VALUES; value++) {
                    parentTransforms[transform][part][value] = symmetry
                            .transform((((long) value) << (part * 8)) & allPegs,
                                    transform);
                }
            }
        }
    }

    private static void validateSymmetry(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final int row, final int column,
            final int bit) {
        final int max = board.boardSize() - 1;
        final int[] expected = { bit, board.bitAt(column, max - row),
                board.bitAt(max - row, max - column),
                board.bitAt(max - column, row), board.bitAt(row, max - column),
                board.bitAt(max - row, column), board.bitAt(column, row),
                board.bitAt(max - column, max - row) };
        for (int transform = 0; transform < expected.length; transform++) {
            if (expected[transform] < 0 || symmetry.transform(1L << bit,
                    transform) != (1L << expected[transform])) {
                throw new IllegalArgumentException(
                        "symmetry must describe the same board geometry");
            }
        }
    }
}
