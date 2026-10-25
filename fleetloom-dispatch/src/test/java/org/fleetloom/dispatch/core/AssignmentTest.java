package org.fleetloom.dispatch.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Random;

import org.junit.jupiter.api.Test;

class AssignmentTest {

    private static final double X = Assignment.FORBIDDEN;

    @Test
    void beatsTakingTheNearestInTurn() {
        // row 0 is nearest to column 0, but only column 0 is near row 1
        int[] match = Assignment.solve(new double[][] {{4, 8}, {4, 16}});

        assertThat(match).containsExactly(1, 0);
    }

    @Test
    void moreRowsThanColumnsLeavesTheCostliestOut() {
        int[] match = Assignment.solve(new double[][] {{5}, {1}, {3}});

        assertThat(match).containsExactly(-1, 0, -1);
    }

    @Test
    void neverUsesForbiddenPairsAndMatchesAsManyAsPossible() {
        // the cheap pair (0, 0) would leave row 1 without a column
        int[] match = Assignment.solve(new double[][] {{1, 100}, {50, X}});

        assertThat(match).containsExactly(1, 0);
        assertThat(Assignment.solve(new double[][] {{X, X}, {X, 2}})).containsExactly(-1, 1);
    }

    @Test
    void emptyInputs() {
        assertThat(Assignment.solve(new double[0][0])).isEmpty();
        assertThat(Assignment.solve(new double[][] {{}, {}})).containsExactly(-1, -1);
    }

    /** Against trying every assignment, on random rectangular matrices with forbidden and negative costs. */
    @Test
    void matchesBruteForce() {
        Random random = new Random(7);
        for (int trial = 0; trial < 500; trial++) {
            int rows = 1 + random.nextInt(5);
            int cols = 1 + random.nextInt(5);
            double[][] cost = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    cost[i][j] = random.nextDouble() < 0.2 ? X : random.nextInt(41) - 10 + random.nextDouble();
                }
            }
            int[] match = Assignment.solve(cost);
            boolean[] used = new boolean[cols];
            int count = 0;
            double total = 0;
            for (int i = 0; i < rows; i++) {
                if (match[i] >= 0) {
                    assertThat(used[match[i]]).isFalse();
                    assertThat(cost[i][match[i]]).isNotEqualTo(X);
                    used[match[i]] = true;
                    count++;
                    total += cost[i][match[i]];
                }
            }
            double[] best = best(cost, 0, new boolean[cols]);
            assertThat(count).isEqualTo((int) best[0]);
            assertThat(total).isCloseTo(best[1], within(1e-9));
        }
    }

    /** {most pairs, least cost with that many} over rows from {@code row} on. */
    private static double[] best(double[][] cost, int row, boolean[] used) {
        if (row == cost.length) {
            return new double[] {0, 0};
        }
        double[] result = best(cost, row + 1, used); // row left out
        for (int j = 0; j < used.length; j++) {
            if (!used[j] && cost[row][j] != X) {
                used[j] = true;
                double[] rest = best(cost, row + 1, used);
                used[j] = false;
                double[] with = {rest[0] + 1, rest[1] + cost[row][j]};
                if (with[0] > result[0] || with[0] == result[0] && with[1] < result[1]) {
                    result = with;
                }
            }
        }
        return result;
    }
}
