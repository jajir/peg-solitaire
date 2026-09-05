package cz.coroptis.pegsolitaire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CompiledBoardKernelTest {

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void everyChildMatchesIndependentOracleAcrossFirstSevenFrontiers(
            final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        Set<Long> frontier = Set
                .of(symmetry.canonicalize(board.initialState()));
        for (int round = 0; round < 7; round++) {
            final Set<Long> next = new HashSet<>();
            for (long state : frontier) {
                final long[] children = assertMatches(board, symmetry, kernel,
                        state);
                for (long child : children) {
                    next.add(child);
                }
            }
            frontier = next;
        }
    }

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void everyTwoPegAndTwoHoleStateMatchesOracle(final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        assertMatches(board, symmetry, kernel, 0L);
        assertMatches(board, symmetry, kernel, board.allPegs());
        for (int first = 0; first < board.holeCount(); first++) {
            assertMatches(board, symmetry, kernel, 1L << first);
            assertMatches(board, symmetry, kernel,
                    board.allPegs() ^ (1L << first));
            for (int second = first + 1; second < board.holeCount(); second++) {
                final long pair = (1L << first) | (1L << second);
                assertMatches(board, symmetry, kernel, pair);
                assertMatches(board, symmetry, kernel, board.allPegs() ^ pair);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void randomStatesAtEveryPegCountMatchOracle(final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        final SplittableRandom random = new SplittableRandom(93718);
        for (int pegCount = 0; pegCount <= board.holeCount(); pegCount++) {
            for (int repetition = 0; repetition < 32; repetition++) {
                long state = 0L;
                while (Long.bitCount(state) < pegCount) {
                    state |= 1L << random.nextInt(board.holeCount());
                }
                assertMatches(board, symmetry, kernel, state);
            }
        }
    }

    @Test
    void senkuJumpsCrossTheRawWordBoundaryInBothDirections() {
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        final int[] rowSteps = { -1, 1, 0, 0 };
        final int[] columnSteps = { 0, 0, -1, 1 };
        int fromLowWord = 0;
        int fromHighWord = 0;
        for (int row = 0; row < board.boardSize(); row++) {
            for (int column = 0; column < board.boardSize(); column++) {
                final int from = board.bitAt(row, column);
                if (from < 0) {
                    continue;
                }
                for (int direction = 0; direction < rowSteps.length; direction++) {
                    final int over = board.bitAt(row + rowSteps[direction],
                            column + columnSteps[direction]);
                    final int toRow = row + 2 * rowSteps[direction];
                    final int toColumn = column + 2 * columnSteps[direction];
                    final int to = board.bitAt(toRow, toColumn);
                    if (over < 0 || to < 0) {
                        continue;
                    }
                    final boolean lowStart = row * board.boardSize()
                            + column < 64;
                    final boolean lowEnd = toRow * board.boardSize()
                            + toColumn < 64;
                    if (lowStart == lowEnd) {
                        continue;
                    }
                    final long state = (1L << from) | (1L << over);
                    final long expectedChild = symmetry.canonicalize(1L << to);
                    final long[] children = assertMatches(board, symmetry,
                            kernel, state);
                    assertTrue(Arrays.stream(children)
                            .anyMatch(child -> child == expectedChild));
                    assertMatches(board, symmetry, kernel,
                            board.allPegs() ^ (1L << to));
                    if (lowStart) {
                        fromLowWord++;
                    } else {
                        fromHighWord++;
                    }
                }
            }
        }
        assertTrue(fromLowWord > 0);
        assertEquals(fromLowWord, fromHighWord);
    }

    @ParameterizedTest
    @EnumSource(BoardVariant.class)
    void preservesOpeningMoveMultiplicityAndFullyOverwritesScratch(
            final BoardVariant variant) {
        final PegSolitaireBoard board = variant.createBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        final long[] scratch = new long[8];
        Arrays.fill(scratch, -1L);
        final List<Long> children = new ArrayList<>();
        assertEquals(4, kernel.generateCanonicalSuccessors(board.initialState(),
                scratch, children::add));
        assertEquals(4, children.size());
        assertEquals(1, new HashSet<>(children).size());
        final long[] expectedTransforms = new long[8];
        symmetry.transformAll(board.initialState(), expectedTransforms);
        assertArrayEquals(expectedTransforms, scratch);
        assertEquals(0,
                kernel.generateCanonicalSuccessors(0L, scratch, child -> {
                    throw new AssertionError(
                            "An empty board has no successors");
                }));
        assertArrayEquals(new long[8], scratch);
    }

    @Test
    void rejectsInvalidArgumentsBeforeInvokingConsumer() {
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        assertThrows(IllegalArgumentException.class,
                () -> CompiledBoardKernel.create(null, symmetry));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledBoardKernel.create(board, null));
        assertThrows(IllegalArgumentException.class,
                () -> CompiledBoardKernel.create(new EnglishBoard(), symmetry));
        assertThrows(IllegalArgumentException.class,
                () -> kernel.generateCanonicalSuccessors(
                        1L << board.holeCount(), new long[8], child -> {
                            throw new AssertionError(
                                    "Invalid state was accepted");
                        }));
        assertThrows(IllegalArgumentException.class,
                () -> kernel.generateCanonicalSuccessors(Long.MIN_VALUE,
                        new long[8], child -> {
                            throw new AssertionError(
                                    "Invalid state was accepted");
                        }));
        assertThrows(IllegalArgumentException.class,
                () -> kernel.generateCanonicalSuccessors(0L, null, child -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> kernel
                .generateCanonicalSuccessors(0L, new long[7], child -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> kernel
                .generateCanonicalSuccessors(0L, new long[9], child -> {
                }));
        assertThrows(IllegalArgumentException.class, () -> kernel
                .generateCanonicalSuccessors(0L, new long[8], null));
    }

    @Test
    void propagatesConsumerFailureWithoutDamagingSharedTables() {
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        final IllegalStateException failure = new IllegalStateException(
                "consumer");
        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> kernel.generateCanonicalSuccessors(board.initialState(),
                        new long[8], child -> {
                            throw failure;
                        })));
        assertMatches(board, symmetry, kernel, board.initialState());
    }

    @Test
    void immutableKernelCanBeSharedAcrossWorkers() throws Exception {
        final PegSolitaireBoard board = new SenkuBoard();
        final BoardSymmetry symmetry = new BoardSymmetry(board);
        final CompiledBoardKernel kernel = CompiledBoardKernel.create(board,
                symmetry);
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            final List<Callable<Integer>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                final int seed = worker;
                tasks.add(() -> {
                    final SplittableRandom random = new SplittableRandom(seed);
                    for (int iteration = 0; iteration < 200; iteration++) {
                        assertMatches(board, symmetry, kernel,
                                random.nextLong() & board.allPegs());
                    }
                    return 200;
                });
            }
            for (Future<Integer> result : executor.invokeAll(tasks)) {
                assertEquals(200, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static long[] assertMatches(final PegSolitaireBoard board,
            final BoardSymmetry symmetry, final CompiledBoardKernel kernel,
            final long state) {
        final long[] expected = new long[board.holeCount() * 4];
        final long[] actual = new long[expected.length];
        final int[] expectedSize = { 0 };
        final int[] actualSize = { 0 };
        final int expectedCount = board.generateSuccessors(state,
                child -> expected[expectedSize[0]++] = symmetry
                        .canonicalize(child));
        final int actualCount = kernel.generateCanonicalSuccessors(state,
                new long[8], child -> actual[actualSize[0]++] = child);
        assertEquals(expectedSize[0], expectedCount);
        assertEquals(actualSize[0], actualCount);
        assertEquals(expectedCount, actualCount,
                () -> "Wrong move count for state " + state);
        Arrays.sort(expected, 0, expectedCount);
        Arrays.sort(actual, 0, actualCount);
        final long[] children = Arrays.copyOf(actual, actualCount);
        assertArrayEquals(Arrays.copyOf(expected, expectedCount), children,
                () -> "Wrong children for state " + state);
        return children;
    }
}
