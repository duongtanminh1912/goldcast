package vn.goldcast.forecast;

/**
 * Feature engineering shared by the machine-learning forecasters.
 *
 * <p>The learning target is the next day's log-return, not the price level. Prices
 * trend and are not stationary, so a model trained on levels learns "the price was
 * around 2 000 last year" and extrapolates nonsense; returns are roughly stationary
 * and comparable across the whole training window.
 *
 * <p>Every feature at index {@code t} is computed from prices up to and including
 * {@code t} only. That is the property that keeps a walk-forward backtest honest: a
 * feature that peeks at {@code t+1} would make the model look clairvoyant in testing
 * and useless in production.
 */
final class MlFeatures {

    /** Number of past prices a single feature row needs (SMA/volatility over 20 returns). */
    static final int LOOKBACK = 20;

    static final String[] NAMES = {
            "ret_lag1", "ret_lag2", "ret_lag3", "ret_lag4", "ret_lag5",
            "ret_mean5", "ret_mean10", "ret_mean20",
            "vol10", "vol20",
            "dist_sma20",
            "rsi14",
    };

    static final int COUNT = NAMES.length;

    private MlFeatures() {}

    /**
     * Supervised training set: features at {@code t}, target = log-return from t to t+1.
     *
     * @param prices strictly positive, oldest first
     */
    static Dataset dataset(double[] prices) {
        int first = LOOKBACK;
        int rows = Math.max(0, prices.length - 1 - first);
        double[][] x = new double[rows][];
        double[] y = new double[rows];
        for (int i = 0; i < rows; i++) {
            int t = first + i;
            x[i] = row(prices, t);
            y[i] = Math.log(prices[t + 1] / prices[t]);
        }
        return new Dataset(x, y);
    }

    /** Feature vector describing the market state at the close of day {@code t}. */
    static double[] row(double[] p, int t) {
        if (t < LOOKBACK || t >= p.length) {
            throw new IllegalArgumentException("Không đủ lịch sử để tính đặc trưng tại t=" + t);
        }
        double[] f = new double[COUNT];

        for (int lag = 0; lag < 5; lag++) {
            f[lag] = ret(p, t - lag);
        }
        f[5] = meanReturn(p, t, 5);
        f[6] = meanReturn(p, t, 10);
        f[7] = meanReturn(p, t, 20);
        f[8] = volatility(p, t, 10);
        f[9] = volatility(p, t, 20);

        double sma = 0;
        for (int i = t - 19; i <= t; i++) {
            sma += p[i];
        }
        sma /= 20;
        f[10] = Math.log(p[t] / sma);

        f[11] = (rsi(p, t, 14) - 50.0) / 50.0;
        return f;
    }

    /** Log-returns of the whole series, used to bound recursive forecasts. */
    static double returnStd(double[] p) {
        int n = p.length - 1;
        if (n < 2) {
            return 0;
        }
        double mean = 0;
        for (int i = 1; i <= n; i++) {
            mean += ret(p, i);
        }
        mean /= n;
        double ss = 0;
        for (int i = 1; i <= n; i++) {
            double d = ret(p, i) - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (n - 1));
    }

    private static double ret(double[] p, int t) {
        return Math.log(p[t] / p[t - 1]);
    }

    private static double meanReturn(double[] p, int t, int window) {
        return Math.log(p[t] / p[t - window]) / window;
    }

    private static double volatility(double[] p, int t, int window) {
        double mean = meanReturn(p, t, window);
        double ss = 0;
        for (int i = t - window + 1; i <= t; i++) {
            double d = ret(p, i) - mean;
            ss += d * d;
        }
        return Math.sqrt(ss / (window - 1));
    }

    /** Simple-average RSI over the last {@code period} changes, 50 when the window is flat. */
    private static double rsi(double[] p, int t, int period) {
        double gain = 0;
        double loss = 0;
        for (int i = t - period + 1; i <= t; i++) {
            double change = p[i] - p[i - 1];
            if (change > 0) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        if (gain + loss == 0) {
            return 50.0;
        }
        return 100.0 * gain / (gain + loss);
    }

    record Dataset(double[][] x, double[] y) {
        int rows() {
            return y.length;
        }

        /** Chronological split: the validation block is always the most recent rows. */
        Dataset head(int count) {
            return new Dataset(java.util.Arrays.copyOfRange(x, 0, count),
                    java.util.Arrays.copyOfRange(y, 0, count));
        }

        Dataset tail(int from) {
            return new Dataset(java.util.Arrays.copyOfRange(x, from, x.length),
                    java.util.Arrays.copyOfRange(y, from, y.length));
        }
    }

    /** A fitted model that maps one feature row to a predicted next-day log-return. */
    interface ReturnModel {
        double predict(double[] features);
    }

    /**
     * Recursive multi-step forecast: predict tomorrow's return, append the implied price,
     * recompute features on the extended series, repeat.
     *
     * <p>Each step's return is clipped to ±3σ of historical daily returns. Feeding a
     * model its own outputs can amplify a small bias into a runaway path; the clip keeps
     * one bad step from compounding across a 90-day horizon.
     */
    static double[] recursive(double[] prices, int horizon, ReturnModel model) {
        double bound = 3.0 * returnStd(prices);
        double[] extended = java.util.Arrays.copyOf(prices, prices.length + horizon);
        double[] out = new double[horizon];
        for (int h = 0; h < horizon; h++) {
            int t = prices.length - 1 + h;
            double r = model.predict(row(extended, t));
            if (!Double.isFinite(r)) {
                r = 0;
            }
            if (bound > 0) {
                r = Math.max(-bound, Math.min(bound, r));
            }
            extended[t + 1] = extended[t] * Math.exp(r);
            out[h] = extended[t + 1];
        }
        return out;
    }
}
