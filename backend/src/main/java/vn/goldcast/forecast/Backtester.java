package vn.goldcast.forecast;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling-origin (walk-forward) evaluation.
 *
 * <p>The model is refitted at each origin using only data available up to that point,
 * then scored against the observations that follow. This is the only evaluation that
 * answers the question a user actually has — "how wrong has this been in the past?" —
 * without leaking future information into the fit.
 */
public final class Backtester {

    /** Evaluating every possible origin is wasteful on long series; this caps the work. */
    private static final int DEFAULT_MAX_ORIGINS = 120;

    private final int maxOrigins;

    public Backtester() {
        this(DEFAULT_MAX_ORIGINS);
    }

    public Backtester(int maxOrigins) {
        if (maxOrigins < 1) {
            throw new IllegalArgumentException("maxOrigins phải >= 1");
        }
        this.maxOrigins = maxOrigins;
    }

    /**
     * @param series   full history, oldest first
     * @param model    the forecaster under test
     * @param horizon  steps ahead to evaluate
     * @param minTrain smallest training window to start from; raised to the model's own
     *                 minimum if that is larger
     * @return a populated result, or an empty one when the history is too short
     */
    public BacktestResult run(double[] series, Forecaster model, int horizon, int minTrain) {
        if (series == null || horizon < 1) {
            return BacktestResult.empty(model == null ? null : model.model(), Math.max(horizon, 1));
        }

        int n = series.length;
        int start = Math.max(minTrain, model.minObservations());
        int lastOrigin = n - horizon;
        if (lastOrigin < start) {
            return BacktestResult.empty(model.model(), horizon);
        }

        int candidates = lastOrigin - start + 1;
        int step = Math.max(1, (int) Math.ceil(candidates / (double) maxOrigins));

        List<List<double[]>> pairsByHorizon = new ArrayList<>(horizon);
        for (int h = 0; h < horizon; h++) {
            pairsByHorizon.add(new ArrayList<>());
        }

        int origins = 0;
        for (int origin = start; origin <= lastOrigin; origin += step) {
            double[] train = new double[origin];
            System.arraycopy(series, 0, train, 0, origin);

            double[] predicted;
            try {
                predicted = model.forecast(train, horizon).values();
            } catch (RuntimeException ex) {
                // A model that cannot be fitted at this origin simply contributes nothing.
                continue;
            }
            origins++;

            for (int h = 0; h < horizon; h++) {
                pairsByHorizon.get(h).add(new double[] {series[origin + h], predicted[h]});
            }
        }

        if (origins == 0) {
            return BacktestResult.empty(model.model(), horizon);
        }

        double naiveScale = Metrics.naiveScale(series);

        List<BacktestResult.HorizonAccuracy> byHorizon = new ArrayList<>(horizon);
        int totalPairs = 0;
        for (List<double[]> pairs : pairsByHorizon) {
            totalPairs += pairs.size();
        }

        double[] allActual = new double[totalPairs];
        double[] allPredicted = new double[totalPairs];
        int cursor = 0;

        for (int h = 0; h < horizon; h++) {
            List<double[]> pairs = pairsByHorizon.get(h);
            double[] actual = new double[pairs.size()];
            double[] predicted = new double[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                actual[i] = pairs.get(i)[0];
                predicted[i] = pairs.get(i)[1];
                allActual[cursor] = actual[i];
                allPredicted[cursor] = predicted[i];
                cursor++;
            }
            Metrics metrics = Metrics.of(actual, predicted, naiveScale);
            byHorizon.add(new BacktestResult.HorizonAccuracy(h + 1, metrics, metrics.rmse()));
        }

        Metrics overall = Metrics.of(allActual, allPredicted, naiveScale);
        return new BacktestResult(model.model(), overall, List.copyOf(byHorizon), origins, horizon, start);
    }
}
