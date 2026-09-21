package vn.goldcast.forecast;

/**
 * A point-forecasting model over a univariate, evenly-spaced series.
 *
 * <p>Implementations are deliberately free of Spring and of persistence concerns so
 * they can be unit-tested (and swapped for an ML-backed implementation) in isolation.
 * Prediction intervals are <em>not</em> produced here: they are derived empirically
 * from out-of-sample errors by {@link IntervalEstimator}, which keeps interval width
 * honest even for models with no closed-form error variance.
 */
public interface Forecaster {

    /** Identifies which catalogue entry this instance implements. */
    ForecastModel model();

    /**
     * Shortest series this model can be fitted on. Callers must check this before
     * calling {@link #forecast}; the engine falls back to a simpler model otherwise.
     */
    int minObservations();

    /**
     * Produces {@code horizon} successive point forecasts.
     *
     * @param series   chronologically ordered observations, oldest first, all strictly positive
     * @param horizon  number of steps ahead, at least 1
     * @return a forecast of exactly {@code horizon} values
     * @throws IllegalArgumentException if the series is shorter than {@link #minObservations()}
     */
    PointForecast forecast(double[] series, int horizon);

    /** Guard shared by every implementation. */
    default void validate(double[] series, int horizon) {
        if (series == null || series.length < minObservations()) {
            throw new IllegalArgumentException(
                    model() + " cần tối thiểu " + minObservations() + " quan sát, nhận được "
                            + (series == null ? 0 : series.length));
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("Horizon phải >= 1, nhận được " + horizon);
        }
    }
}
