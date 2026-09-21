package vn.goldcast.forecast;

import java.util.List;

/**
 * Outcome of a rolling-origin evaluation.
 *
 * @param model      the model that was evaluated
 * @param overall    accuracy pooled across every origin and every horizon step
 * @param byHorizon  accuracy broken down by steps ahead — error grows with h, and hiding
 *                   that behind a single average is how forecasts get oversold
 * @param origins    how many train/test splits were evaluated
 * @param horizon    maximum steps ahead evaluated
 * @param minTrain   size of the smallest training window used
 */
public record BacktestResult(
        ForecastModel model,
        Metrics overall,
        List<HorizonAccuracy> byHorizon,
        int origins,
        int horizon,
        int minTrain) {

    /**
     * Accuracy at one specific number of steps ahead.
     *
     * @param step    steps ahead, 1-based
     * @param metrics accuracy at this step
     * @param rmse    convenience copy of {@code metrics.rmse()}, used to size prediction intervals
     */
    public record HorizonAccuracy(int step, Metrics metrics, double rmse) {}

    public static BacktestResult empty(ForecastModel model, int horizon) {
        return new BacktestResult(model, Metrics.EMPTY, List.of(), 0, horizon, 0);
    }

    public boolean hasData() {
        return origins > 0 && overall.sample() > 0;
    }

    /** Out-of-sample RMSE at {@code step} steps ahead, or {@code NaN} if not measured. */
    public double rmseAt(int step) {
        for (HorizonAccuracy accuracy : byHorizon) {
            if (accuracy.step() == step) {
                return accuracy.rmse();
            }
        }
        return Double.NaN;
    }

    /** Number of out-of-sample observations behind the estimate at {@code step}. */
    public int sampleAt(int step) {
        for (HorizonAccuracy accuracy : byHorizon) {
            if (accuracy.step() == step) {
                return accuracy.metrics().sample();
            }
        }
        return 0;
    }
}
