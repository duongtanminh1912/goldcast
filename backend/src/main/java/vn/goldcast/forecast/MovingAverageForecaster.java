package vn.goldcast.forecast;

import java.util.Arrays;

/**
 * Flat forecast at the mean of the last {@code window} observations.
 *
 * <p>Deliberately biased towards the recent average rather than the last tick, which
 * makes it more stable than {@link NaiveForecaster} on noisy dealer quotes but slower
 * to react to a real move.
 */
public final class MovingAverageForecaster implements Forecaster {

    private final int window;

    public MovingAverageForecaster(int window) {
        if (window < 2) {
            throw new IllegalArgumentException("Cửa sổ SMA phải >= 2, nhận được " + window);
        }
        this.window = window;
    }

    public int window() {
        return window;
    }

    @Override
    public ForecastModel model() {
        return ForecastModel.SMA;
    }

    @Override
    public int minObservations() {
        return window;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);
        double sum = 0;
        for (int i = series.length - window; i < series.length; i++) {
            sum += series[i];
        }
        double mean = sum / window;

        double[] out = new double[horizon];
        Arrays.fill(out, mean);
        return new PointForecast(model(), out, PointForecast.params("window", window, "mean", mean));
    }
}
