package vn.goldcast.forecast;

/**
 * Holt's linear trend method with a damping parameter (Gardner–McKenzie).
 *
 * <pre>
 *   ŷ(t+h) = l(t) + (φ + φ² + … + φ^h) · b(t)
 *   l(t)   = α·y(t) + (1−α)·(l(t−1) + φ·b(t−1))
 *   b(t)   = β·(l(t) − l(t−1)) + (1−β)·φ·b(t−1)
 * </pre>
 *
 * <p>Damping matters here: an undamped trend fitted to a gold rally extrapolates that
 * rally forever, which is exactly the failure mode that makes naive price-prediction
 * sites embarrassing. With φ &lt; 1 the forecast converges to a finite asymptote.
 *
 * <p>α, β and φ are chosen by grid search on the sum of squared one-step-ahead errors.
 * The grid is small and fixed, so fitting is deterministic and costs microseconds.
 */
public final class HoltDampedForecaster implements Forecaster {

    private static final double[] ALPHA_GRID = {0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.65, 0.8, 0.95};
    private static final double[] BETA_GRID = {0.01, 0.05, 0.1, 0.2, 0.35};
    private static final double[] PHI_GRID = {0.80, 0.90, 0.95, 0.98, 1.0};

    @Override
    public ForecastModel model() {
        return ForecastModel.HOLT_DAMPED;
    }

    @Override
    public int minObservations() {
        return 10;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);

        double bestSse = Double.POSITIVE_INFINITY;
        double bestAlpha = 0.3, bestBeta = 0.05, bestPhi = 0.95;

        for (double alpha : ALPHA_GRID) {
            for (double beta : BETA_GRID) {
                for (double phi : PHI_GRID) {
                    double sse = sse(series, alpha, beta, phi);
                    if (sse < bestSse) {
                        bestSse = sse;
                        bestAlpha = alpha;
                        bestBeta = beta;
                        bestPhi = phi;
                    }
                }
            }
        }

        State state = run(series, bestAlpha, bestBeta, bestPhi);
        double[] out = new double[horizon];
        double phiSum = 0;
        double phiPow = 1;
        for (int h = 1; h <= horizon; h++) {
            phiPow *= bestPhi;
            phiSum += phiPow;
            out[h - 1] = Math.max(state.level + phiSum * state.trend, 1e-9);
        }

        int n = series.length;
        double rmse = Math.sqrt(bestSse / Math.max(1, n - 1));
        return new PointForecast(model(), out, PointForecast.params(
                "alpha", bestAlpha, "beta", bestBeta, "phi", bestPhi, "trainRmse", rmse));
    }

    private static double sse(double[] y, double alpha, double beta, double phi) {
        double level = y[0];
        double trend = y[1] - y[0];
        double sse = 0;
        for (int t = 1; t < y.length; t++) {
            double forecast = level + phi * trend;
            double error = y[t] - forecast;
            sse += error * error;

            double prevLevel = level;
            level = alpha * y[t] + (1 - alpha) * forecast;
            trend = beta * (level - prevLevel) + (1 - beta) * phi * trend;
        }
        return sse;
    }

    private static State run(double[] y, double alpha, double beta, double phi) {
        double level = y[0];
        double trend = y[1] - y[0];
        for (int t = 1; t < y.length; t++) {
            double forecast = level + phi * trend;
            double prevLevel = level;
            level = alpha * y[t] + (1 - alpha) * forecast;
            trend = beta * (level - prevLevel) + (1 - beta) * phi * trend;
        }
        return new State(level, trend);
    }

    private record State(double level, double trend) {}
}
