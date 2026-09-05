package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.hestiastore.index.chunkentryfile.KeyPageCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardStateEncodingTest {

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void everySymmetryPreservesTheCompleteAffineParityClass(
            final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final KeyPageCodec<Long> codec = new BoardStateEncoding(board)
                .codecForPopulation(board.holeCount() - 1);
        final long[] masks = codec.getFixedWeightParityMasks();
        final Set<Long> rowSpace = new TreeSet<>();
        for (int subset = 0; subset < (1 << masks.length); subset++) {
            long combined = 0L;
            for (int index = 0; index < masks.length; index++) {
                if ((subset & (1 << index)) != 0) {
                    combined ^= masks[index];
                }
            }
            rowSpace.add(combined);
        }
        assertEquals(16, rowSpace.size(),
                "The four parity equations must be independent");

        // Closure of the whole equation space is independent of population:
        // it proves every key in the affine class remains in that class after
        // canonicalization, not merely keys reached in a few early rounds.
        final long[] transformed = new long[BoardSymmetry.TRANSFORM_COUNT];
        for (final long mask : masks) {
            symmetry.transformAll(mask, transformed);
            for (int transform = 0; transform < transformed.length; transform++) {
                assertTrue(rowSpace.contains(transformed[transform]),
                        variant + " parity row-space closure under transform "
                                + transform);
            }
        }
        symmetry.transformAll(board.initialState(), transformed);
        for (int transform = 0; transform < transformed.length; transform++) {
            int syndrome = 0;
            for (int index = 0; index < masks.length; index++) {
                syndrome |= (Long.bitCount(
                        transformed[transform] & masks[index]) & 1) << index;
            }
            assertEquals(codec.getFixedWeightParitySyndrome(), syndrome, variant
                    + " initial affine class under transform " + transform);
        }
    }

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void everyOrthogonalJumpPreservesAllFourMasks(final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final KeyPageCodec<Long> codec = new BoardStateEncoding(board)
                .codecForPopulation(board.holeCount() - 1);
        final long[] masks = codec.getFixedWeightParityMasks();
        assertEquals(4, masks.length);
        int jumps = 0;
        for (int first = 0; first < board.holeCount(); first++) {
            for (int second = first + 1; second < board.holeCount(); second++) {
                final long source = (1L << first) | (1L << second);
                jumps += board.generateSuccessors(source, target -> {
                    for (final long mask : masks) {
                        assertEquals(0,
                                Long.bitCount((source ^ target) & mask) & 1);
                    }
                });
            }
        }
        assertTrue(jumps > 0);
    }

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void canonicalFrontiersMatchPersistedDomain(final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final BoardStateEncoding encoding = new BoardStateEncoding(board);
        Set<Long> frontier = Set
                .of(symmetry.canonicalize(board.initialState()));
        for (int round = 1; round <= 7; round++) {
            final KeyPageCodec<Long> codec = encoding
                    .codecForPopulation(board.holeCount() - round);
            final Set<Long> next = new TreeSet<>();
            final SortedStateSampler sample = new SortedStateSampler(
                    board.holeCount());
            for (final long key : new TreeSet<>(frontier)) {
                sample.add(key);
                int syndrome = 0;
                final long[] masks = codec.getFixedWeightParityMasks();
                for (int index = 0; index < masks.length; index++) {
                    syndrome |= (Long.bitCount(key & masks[index])
                            & 1) << index;
                }
                assertEquals(codec.getFixedWeightSetBitCount(),
                        Long.bitCount(key));
                assertEquals(codec.getFixedWeightParitySyndrome(), syndrome);
                board.generateSuccessors(key,
                        child -> next.add(symmetry.canonicalize(child)));
            }
            assertEquals(board.holeCount() - round - 1,
                    encoding.codecForSuccessors(sample.snapshot())
                            .getFixedWeightSetBitCount());
            frontier = next;
        }
    }

    @Test
    void terminalPopulationsAllowEmptyIndexesEvenWhenParityDomainIsEmpty() {
        final BoardStateEncoding encoding = new BoardStateEncoding(
                new SenkuBoard());
        assertFalse(
                encoding.codecForPopulation(0).isLongFixedWeightDeltaVarint());
        assertFalse(
                encoding.codecForPopulation(1).isLongFixedWeightDeltaVarint());
        assertThrows(IllegalArgumentException.class,
                () -> encoding.codecForPopulation(-1));
        assertThrows(IllegalArgumentException.class,
                () -> encoding.codecForPopulation(50));
    }

    @Test
    void manuallySuppliedOtherClassUsesPopulationOnly() {
        final BoardStateEncoding encoding = new BoardStateEncoding(
                new SenkuBoard());
        final KeyPageCodec<Long> codec = encoding.codecForSuccessors(
                new RoundStateSample(49, 1, 1, new long[] { 7L }));
        assertTrue(codec.isLongFixedWeightDeltaVarint());
        assertEquals(2, codec.getFixedWeightSetBitCount());
        assertEquals(0, codec.getFixedWeightParityMasks().length);
    }

    @Test
    void rejectsInvalidSourceSamples() {
        final BoardStateEncoding encoding = new BoardStateEncoding(
                new SenkuBoard());
        assertThrows(NullPointerException.class,
                () -> new BoardStateEncoding(null));
        assertThrows(NullPointerException.class,
                () -> encoding.codecForSuccessors(null));
        assertThrows(IllegalArgumentException.class,
                () -> encoding.codecForSuccessors(
                        new RoundStateSample(49, 0, 1, new long[0])));
        assertThrows(IllegalArgumentException.class,
                () -> encoding.codecForSuccessors(
                        new RoundStateSample(33, 1, 1, new long[] { 1L })));
        assertThrows(IllegalArgumentException.class,
                () -> encoding.codecForSuccessors(
                        new RoundStateSample(49, 2, 1, new long[] { 1L, 3L })));
    }
}
