package vn.goldcast.forecast;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dung som khi SSE vuot muc tot nhat khong duoc doi tham so ma luoi chon. */
class HoltDampedSearchTest {

    private static final double[] ALPHA = {0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.65, 0.8, 0.95};
    private static final double[] BETA = {0.01, 0.05, 0.1, 0.2, 0.35};
    private static final double[] PHI = {0.80, 0.90, 0.95, 0.98, 1.0};

    /** Ban tham chieu: tinh tron SSE cho moi to hop, nhu truoc khi co dung som. */
    private static double[] fullSearch(double[] y) {
        double best = Double.POSITIVE_INFINITY;
        double[] chosen = {0.3, 0.05, 0.95, Double.NaN};
        for (double a : ALPHA) {
            for (double b : BETA) {
                for (double p : PHI) {
                    double level = y[0], trend = y[1] - y[0], sse = 0;
                    for (int t = 1; t < y.length; t++) {
                        double f = level + p * trend;
                        sse += (y[t] - f) * (y[t] - f);
                        double prev = level;
                        level = a * y[t] + (1 - a) * f;
                        trend = b * (level - prev) + (1 - b) * p * trend;
                    }
                    if (sse < best) {
                        best = sse;
                        chosen = new double[] {a, b, p, Math.sqrt(sse / Math.max(1, y.length - 1))};
                    }
                }
            }
        }
        return chosen;
    }

    @Test
    void earlyExitPicksTheSameParametersAsTheFullGrid() {
        Random random = new Random(20261124);
        for (int run = 0; run < 200; run++) {
            int n = 12 + random.nextInt(700);
            double[] y = new double[n];
            double drift = random.nextGaussian() * 2;
            y[0] = 1000 + random.nextDouble() * 2000;
            for (int t = 1; t < n; t++) {
                y[t] = Math.max(1, y[t - 1] + drift + random.nextGaussian() * (5 + run % 40));
            }
            double[] expected = fullSearch(y);
            var params = new HoltDampedForecaster().forecast(y, 5).params();
            double[] actual = {params.get("alpha"), params.get("beta"), params.get("phi"), params.get("trainRmse")};
            assertArrayEquals(expected, actual, 1e-9, "run " + run + ", n=" + n);
        }
    }

    @Test
    void flatSeriesStillReturnsTheFirstBestCombination() {
        double[] flat = new double[40];
        java.util.Arrays.fill(flat, 2000);
        var params = new HoltDampedForecaster().forecast(flat, 3).params();
        double[] expected = fullSearch(flat);
        assertEquals(expected[0], params.get("alpha"));
        assertEquals(expected[1], params.get("beta"));
        assertEquals(expected[2], params.get("phi"));
    }
}
