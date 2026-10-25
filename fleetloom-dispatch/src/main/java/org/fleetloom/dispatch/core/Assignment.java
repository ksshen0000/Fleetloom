package org.fleetloom.dispatch.core;

import java.util.Arrays;

/**
 * Minimum-cost assignment of rows to columns: the Hungarian method of Kuhn (1955) and Munkres (1957),
 * in the O(n²·m) shortest-augmenting-path form with potentials (as in Jonker and Volgenant, 1987).
 * The matrix may be rectangular; the smaller side is matched completely. Pairs that must not be matched
 * cost {@link #FORBIDDEN}: the result matches as many allowed pairs as possible first, and among those
 * takes the cheapest, and never returns a forbidden pair.
 */
final class Assignment {

    static final double FORBIDDEN = Double.POSITIVE_INFINITY;

    private Assignment() {
    }

    /** @return the column of each row, or -1 for rows left without one */
    static int[] solve(double[][] cost) {
        int rows = cost.length;
        int cols = rows == 0 ? 0 : cost[0].length;
        int[] result = new int[rows];
        Arrays.fill(result, -1);
        if (rows == 0 || cols == 0) {
            return result;
        }
        // forbidden pairs become one cost larger than any set of allowed ones, so they are taken last
        double span = 0;
        for (double[] row : cost) {
            for (double c : row) {
                if (c != FORBIDDEN) {
                    span = Math.max(span, Math.abs(c));
                }
            }
        }
        double big = (span + 1) * (Math.min(rows, cols) + 1) * 2;
        boolean transpose = rows > cols;
        int n = transpose ? cols : rows;
        int m = transpose ? rows : cols;
        double[][] a = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double c = transpose ? cost[j][i] : cost[i][j];
                a[i][j] = c == FORBIDDEN ? big : c;
            }
        }
        int[] match = hungarian(a, n, m);
        for (int i = 0; i < n; i++) {
            int j = match[i];
            int row = transpose ? j : i;
            int col = transpose ? i : j;
            if (cost[row][col] != FORBIDDEN) {
                result[row] = col;
            }
        }
        return result;
    }

    /** n ≤ m; returns the column matched to each row. 1-based internally, column 0 is the virtual start. */
    private static int[] hungarian(double[][] a, int n, int m) {
        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];   // p[j]: row matched to column j
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] match = new int[n];
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                match[p[j] - 1] = j - 1;
            }
        }
        return match;
    }
}
