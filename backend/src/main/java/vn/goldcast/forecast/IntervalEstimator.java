package vn.goldcast.forecast;

/**
 * Turns measured out-of-sample error into prediction intervals.
 *
 * <p>Intervals are sized from the backtested RMSE at each horizon step rather than from
 * a model's in-sample residuals. In-sample residuals are always too small — the model
 * has already seen those points — and intervals built from them are the main reason
 * forecast dashboards look far more confident than they deserve to.
 *
 * <p>When a horizon has too few backtested observations to trust, width falls back to
 * the one-step error scaled by √h, the standard random-walk growth rate.
 */
public final class IntervalEstimator {

    /** z for a two-sided 80% interval. */
    private static final double Z80 = 1.2815515655446004;
    /** z for a two-sided 95% interval. */
    private static final double Z95 = 1.959963984540054;

    /** Below this many backtested errors, the per-horizon RMSE is too noisy to use directly. */
    private static final int MIN_SAMPLES = 5;

    private IntervalEstimator() {}

    /**
     * @param point       the point forecast being bracketed
     * @param step        steps ahead, 1-based
     * @param backtest    measured accuracy, may be empty
     * @param fallbackSigma one-step error scale used when the backtest cannot supply one,
     *                      typically the standard deviation of first differences
     */
    public static PredictionInterval estimate(
            double point, int step, BacktestResult backtest, double fallbackSigma) {

        double sigma = sigmaFor(step, backtest, fallbackSigma);
        if (!Double.isFinite(sigma) || sigma <= 0) {
            return new PredictionInterval(point, point, point, point);
        }

        return new PredictionInterval(
                floor(point - Z80 * sigma),
                point + Z80 * sigma,
                floor(point - Z95 * sigma),
                point + Z95 * sigma);
    }

    private static double sigmaFor(int step, BacktestResult backtest, double fallbackSigma) {
        if (backtest != null && backtest.hasData()) {
            double rmse = backtest.rmseAt(step);
            if (Double.isFinite(rmse) && rmse > 0 && backtest.sampleAt(step) >= MIN_SAMPLES) {
                return rmse;
            }
            double oneStep = backtest.rmseAt(1);
            if (Double.isFinite(oneStep) && oneStep > 0 && backtest.sampleAt(1) >= MIN_SAMPLES) {
                return oneStep * Math.sqrt(step);
            }
        }
        return fallbackSigma * Math.sqrt(step);
    }

    /** Standard deviation of first differences — the fallback one-step error scale. */
    public static double diffSigma(double[] series) {
        if (series == null || series.length < 3) {
            return Double.NaN;
        }
        int n = series.length - 1;
        double mean = 0;
        for (int i = 1; i < series.length; i++) {
            mean += series[i] - series[i - 1];
        }
        mean /= n;

        double sumSq = 0;
        for (int i = 1; i < series.length; i++) {
            double d = (series[i] - series[i - 1]) - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / Math.max(1, n - 1));
    }

    /** Prices have a hard floor at zero; an interval crossing it would be nonsense. */
    private static double floor(double value) {
        return Math.max(value, 0.0);
    }
}
