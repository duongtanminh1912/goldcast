package vn.goldcast.forecast;

/**
 * Random walk with drift: continues the average per-period change observed over the
 * training window. Equivalent to drawing a straight line through the first and last
 * observation and extending it.
 */
public final class DriftForecaster implements Forecaster {

    @Override
    public ForecastModel model() {
        return ForecastModel.DRIFT;
    }

    @Override
    public int minObservations() {
        return 2;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);
        int n = series.length;
        double last = series[n - 1];
        double drift = (last - series[0]) / (n - 1);

        double[] out = new double[horizon];
        for (int h = 1; h <= horizon; h++) {
            // Prices cannot go negative; clamp rather than emit a nonsensical path.
            out[h - 1] = Math.max(last + h * drift, 1e-9);
        }
        return new PointForecast(model(), out, PointForecast.params("drift", drift, "last", last));
    }
}
