package vn.goldcast.forecast;

/** Minimal dense linear algebra — just enough to fit an OLS regression without a dependency. */
final class LinearAlgebra {

    private LinearAlgebra() {}

    /**
     * Solves {@code (XᵀX + λI) β = Xᵀy} by Gaussian elimination with partial pivoting.
     *
     * <p>The ridge term λ is tiny and proportional to the trace: it does not meaningfully
     * bias the fit, but it keeps the solve stable when regressors are near-collinear,
     * which happens routinely on smooth price series.
     *
     * @return the coefficient vector, or {@code null} if the system is singular even with ridge
     */
    static double[] solveOls(double[][] x, double[] y) {
        int rows = x.length;
        int cols = x[0].length;

        double[][] xtx = new double[cols][cols];
        double[] xty = new double[cols];
        for (int r = 0; r < rows; r++) {
            double[] row = x[r];
            for (int i = 0; i < cols; i++) {
                xty[i] += row[i] * y[r];
                for (int j = i; j < cols; j++) {
                    xtx[i][j] += row[i] * row[j];
                }
            }
        }
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < i; j++) {
                xtx[i][j] = xtx[j][i];
            }
        }

        double trace = 0;
        for (int i = 0; i < cols; i++) {
            trace += xtx[i][i];
        }
        double ridge = Math.max(trace, 1.0) * 1e-10;
        for (int i = 0; i < cols; i++) {
            xtx[i][i] += ridge;
        }

        return gaussianSolve(xtx, xty);
    }

    private static double[] gaussianSolve(double[][] a, double[] b) {
        int n = b.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n] = b[i];
        }

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][col]) < 1e-12) {
                return null;
            }
            double[] tmp = m[col];
            m[col] = m[pivot];
            m[pivot] = tmp;

            for (int r = col + 1; r < n; r++) {
                double factor = m[r][col] / m[col][col];
                if (factor == 0) {
                    continue;
                }
                for (int c = col; c <= n; c++) {
                    m[r][c] -= factor * m[col][c];
                }
            }
        }

        double[] out = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double sum = m[r][n];
            for (int c = r + 1; c < n; c++) {
                sum -= m[r][c] * out[c];
            }
            out[r] = sum / m[r][r];
            if (!Double.isFinite(out[r])) {
                return null;
            }
        }
        return out;
    }
}
