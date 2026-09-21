package vn.goldcast.forecast;

import java.util.Arrays;

/**
 * Random walk: every future value equals the last observation.
 *
 * <p>For daily precious-metal prices this is a genuinely strong benchmark — it is the
 * model MASE is scaled against, and a "clever" model that cannot beat it is noise.
 */
public final class NaiveForecaster implements Forecaster {

    @Override
    public ForecastModel model() {
        return ForecastModel.NAIVE;
    }

    @Override
    public int minObservations() {
        return 1;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);
        double last = series[series.length - 1];
        double[] out = new double[horizon];
        Arrays.fill(out, last);
        return new PointForecast(model(), out, PointForecast.params("last", last));
    }
}
