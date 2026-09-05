package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

class RangeShardRouterTest {

    @Test
    void boundaryBelongsToRightRangeIncludingSignedLongExtremes() {
        final RangeShardRouter router = new RangeShardRouter(
                new long[] { Long.MIN_VALUE, -7L, 0L, Long.MAX_VALUE });

        assertEquals(1, router.shard(Long.MIN_VALUE));
        assertEquals(1, router.shard(-8L));
        assertEquals(2, router.shard(-7L));
        assertEquals(2, router.shard(-1L));
        assertEquals(3, router.shard(0L));
        assertEquals(3, router.shard(Long.MAX_VALUE - 1));
        assertEquals(4, router.shard(Long.MAX_VALUE));
    }

    @Test
    void preservesHashEntropyWithinEachRange() {
        final RangeShardRouter router = new RangeShardRouter(
                new long[] { 100_000L });
        final Set<Integer> hashes = new HashSet<>();
        for (long key = 0; key < 4096; key++) {
            final int hash = router.applyAsInt(key);
            assertEquals(0, Math.floorMod(hash, RangeShardRouter.SHARD_COUNT));
            hashes.add(hash);
        }
        assertTrue(hashes.size() > 4000,
                "A range ID alone would collide in Hestia's maps");
        for (long key = 100_000; key < 104_096; key++) {
            assertEquals(1, Math.floorMod(router.hash(key),
                    RangeShardRouter.SHARD_COUNT));
        }
    }

    @Test
    void rangesAreImmutableAndValidated() {
        final long[] boundaries = { 10L, 20L };
        final RangeShardRouter router = new RangeShardRouter(boundaries);
        boundaries[0] = 1L;

        assertEquals(0, router.shard(9L));
        assertEquals(1, router.shard(10L));
        assertEquals(2, router.shard(20L));
        assertThrows(IllegalArgumentException.class,
                () -> new RangeShardRouter(new long[] { 2L, 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RangeShardRouter(new long[] { 1L, 1L }));
        assertThrows(IllegalArgumentException.class,
                () -> new RangeShardRouter(new long[128]));
    }

    @Test
    void fullSmallSourceForecastBalancesCanonicalSuccessors() {
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        Set<Long> source = Set.of(symmetry.canonicalize(board.initialState()));
        for (int round = 2; round <= 4; round++) {
            source = successors(source, board, symmetry);
        }
        final SortedStateSampler sampler = new SortedStateSampler(
                board.holeCount());
        source.forEach(sampler::add);
        final RangeShardRouter router = RangeShardRouter
                .fromSourceSample(sampler.snapshot(), board, symmetry);
        final Set<Long> destination = successors(source, board, symmetry);
        assertEquals(105, destination.size());

        int expectedShard = 0;
        for (final long state : destination) {
            assertEquals(expectedShard++, router.shard(state));
        }
    }

    @Test
    void emptyForecastIsStillAValidFullKeyHashRouter() {
        final PegSolitaireBoard board = new SenkuBoard();
        final RangeShardRouter router = RangeShardRouter.fromSourceSample(
                new RoundStateSample(49, 1L, 1L, new long[] { 1L }), board,
                new BoardSymmetry(board));

        assertEquals(0, router.shard(0L));
        assertEquals(0, router.shard(Long.MAX_VALUE));
    }

    @Test
    void rejectsSampleFromDifferentBoard() {
        final PegSolitaireBoard board = new SenkuBoard();
        assertThrows(IllegalArgumentException.class,
                () -> RangeShardRouter.fromSourceSample(
                        new RoundStateSample(33, 0L, 1L, new long[0]), board,
                        new BoardSymmetry(board)));
    }

    private Set<Long> successors(final Set<Long> states,
            final PegSolitaireBoard board, final BoardSymmetry symmetry) {
        final Set<Long> result = new TreeSet<>();
        for (final long state : states) {
            board.generateSuccessors(state,
                    next -> result.add(symmetry.canonicalize(next)));
        }
        return result;
    }
}
