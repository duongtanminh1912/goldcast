package vn.goldcast.forecast;

/**
 * Autoregression of order p on first differences, fitted by ordinary least squares —
 * in Box–Jenkins terms an ARIMA(p, 1, 0) without the moving-average part.
 *
 * <pre>
 *   d(t) = y(t) − y(t−1)
 *   d(t) = c + φ₁·d(t−1) + … + φ_p·d(t−p) + ε(t)
 * </pre>
 *
 * <p>Differencing is what makes this usable on a price level that trends: the raw
 * series is not stationary, its day-to-day change roughly is. Multi-step forecasts are
 * produced recursively, feeding each predicted difference back in as an input.
 *
 * <p>If the design matrix turns out to be singular the forecaster degrades to
 * {@link DriftForecaster} rather than returning garbage.
 */
public final class ArDiffForecaster implements Forecaster {

    private final int order;

    public ArDiffForecaster(int order) {
        if (order < 1 || order > 20) {
            throw new IllegalArgumentException("Bậc AR phải nằm trong 1..20, nhận được " + order);
        }
        this.order = order;
    }

    public int order() {
        return order;
    }

    @Override
    public ForecastModel model() {
        return ForecastModel.AR_DIFF;
    }

    @Override
    public int minObservations() {
        // p lags + intercept, plus enough residual degrees of freedom to be meaningful.
        return 3 * order + 12;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);

        int n = series.length;
        double[] diffs = new double[n - 1];
        for (int i = 1; i < n; i++) {
            diffs[i - 1] = series[i] - series[i - 1];
        }

        int rows = diffs.length - order;
        double[][] design = new double[rows][order + 1];
        double[] target = new double[rows];
        for (int i = 0; i < rows; i++) {
            design[i][0] = 1.0;
            for (int lag = 1; lag <= order; lag++) {
                design[i][lag] = diffs[order + i - lag];
            }
            target[i] = diffs[order + i];
        }

        double[] coefficients = LinearAlgebra.solveOls(design, target);
        if (coefficients == null) {
            return new DriftForecaster().forecast(series, horizon);
        }

        // Recursive multi-step: the most recent p differences, newest first.
        double[] window = new double[order];
        for (int lag = 1; lag <= order; lag++) {
            window[lag - 1] = diffs[diffs.length - lag];
        }

        double level = series[n - 1];
        double[] out = new double[horizon];
        for (int h = 0; h < horizon; h++) {
            double next = coefficients[0];
            for (int lag = 1; lag <= order; lag++) {
                next += coefficients[lag] * window[lag - 1];
            }
            if (!Double.isFinite(next)) {
                return new DriftForecaster().forecast(series, horizon);
            }
            level = Math.max(level + next, 1e-9);
            out[h] = level;

            System.arraycopy(window, 0, window, 1, order - 1);
            window[0] = next;
        }

        double sse = 0;
        for (int i = 0; i < rows; i++) {
            double fitted = coefficients[0];
            for (int lag = 1; lag <= order; lag++) {
                fitted += coefficients[lag] * design[i][lag];
            }
            double error = target[i] - fitted;
            sse += error * error;
        }

        java.util.Map<String, Double> params = new java.util.LinkedHashMap<>();
        params.put("order", (double) order);
        params.put("intercept", coefficients[0]);
        for (int lag = 1; lag <= order; lag++) {
            params.put("phi" + lag, coefficients[lag]);
        }
        params.put("trainRmse", Math.sqrt(sse / Math.max(1, rows)));

        return new PointForecast(model(), out, params);
    }
}
