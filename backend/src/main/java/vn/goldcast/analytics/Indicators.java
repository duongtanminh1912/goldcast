package vn.goldcast.analytics;

/**
 * Classical technical indicators over a closing-price series.
 *
 * <p>Every method returns an array the same length as its input, with {@code NaN} in the
 * leading positions where the indicator is not yet defined. Callers serialise NaN as
 * {@code null} so the chart simply starts later instead of drawing a line from zero.
 *
 * <p>Pure functions, no state, no framework — so they are cheap to test and safe to call
 * from anywhere.
 */
public final class Indicators {

    private Indicators() {}

    /** Simple moving average. */
    public static double[] sma(double[] series, int window) {
        requireSeries(series);
        requireWindow(window);
        double[] out = filled(series.length);
        if (series.length < window) {
            return out;
        }

        double sum = 0;
        for (int i = 0; i < series.length; i++) {
            sum += series[i];
            if (i >= window) {
                sum -= series[i - window];
            }
            if (i >= window - 1) {
                out[i] = sum / window;
            }
        }
        return out;
    }

    /**
     * Exponential moving average, seeded with the SMA of the first {@code window} points
     * so the early values are not dominated by the single first observation.
     */
    public static double[] ema(double[] series, int window) {
        requireSeries(series);
        requireWindow(window);
        double[] out = filled(series.length);
        if (series.length < window) {
            return out;
        }

        double multiplier = 2.0 / (window + 1);
        double seed = 0;
        for (int i = 0; i < window; i++) {
            seed += series[i];
        }
        seed /= window;
        out[window - 1] = seed;

        for (int i = window; i < series.length; i++) {
            out[i] = (series[i] - out[i - 1]) * multiplier + out[i - 1];
        }
        return out;
    }

    /**
     * Relative Strength Index using Wilder's smoothing (the original definition, not a
     * plain moving average of gains and losses).
     *
     * @return values in [0, 100]
     */
    public static double[] rsi(double[] series, int period) {
        requireSeries(series);
        if (period < 2) {
            throw new IllegalArgumentException("Chu kỳ RSI phải >= 2, nhận được " + period);
        }
        double[] out = filled(series.length);
        if (series.length <= period) {
            return out;
        }

        double gainSum = 0;
        double lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = series[i] - series[i - 1];
            if (change >= 0) {
                gainSum += change;
            } else {
                lossSum -= change;
            }
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        out[period] = rsiValue(avgGain, avgLoss);

        for (int i = period + 1; i < series.length; i++) {
            double change = series[i] - series[i - 1];
            double gain = change > 0 ? change : 0;
            double loss = change < 0 ? -change : 0;
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) {
            return avgGain == 0 ? 50.0 : 100.0;
        }
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Bollinger Bands: a moving average with bands at ±k population standard deviations.
     *
     * @param k number of standard deviations, conventionally 2
     */
    public static Bands bollinger(double[] series, int window, double k) {
        requireSeries(series);
        requireWindow(window);
        if (k <= 0) {
            throw new IllegalArgumentException("Hệ số k phải > 0, nhận được " + k);
        }

        double[] middle = sma(series, window);
        double[] upper = filled(series.length);
        double[] lower = filled(series.length);

        for (int i = window - 1; i < series.length; i++) {
            double mean = middle[i];
            if (Double.isNaN(mean)) {
                continue;
            }
            double sumSq = 0;
            for (int j = i - window + 1; j <= i; j++) {
                double d = series[j] - mean;
                sumSq += d * d;
            }
            double sd = Math.sqrt(sumSq / window);
            upper[i] = mean + k * sd;
            lower[i] = mean - k * sd;
        }
        return new Bands(middle, upper, lower);
    }

    /** Annualised volatility from daily log returns, expressed in percent. */
    public static double annualisedVolatility(double[] series, int tradingDaysPerYear) {
        requireSeries(series);
        if (series.length < 3) {
            return Double.NaN;
        }
        int n = series.length - 1;
        double[] returns = new double[n];
        double mean = 0;
        for (int i = 1; i < series.length; i++) {
            returns[i - 1] = Math.log(series[i] / series[i - 1]);
            mean += returns[i - 1];
        }
        mean /= n;

        double sumSq = 0;
        for (double r : returns) {
            double d = r - mean;
            sumSq += d * d;
        }
        double daily = Math.sqrt(sumSq / Math.max(1, n - 1));
        return daily * Math.sqrt(tradingDaysPerYear) * 100.0;
    }

    /** Percentage change between the last value and the value {@code lookback} steps earlier. */
    public static double changePercent(double[] series, int lookback) {
        requireSeries(series);
        int n = series.length;
        if (lookback < 1 || n <= lookback) {
            return Double.NaN;
        }
        double previous = series[n - 1 - lookback];
        if (previous == 0) {
            return Double.NaN;
        }
        return (series[n - 1] - previous) / previous * 100.0;
    }

    /** The three Bollinger series, aligned with the input. */
    public record Bands(double[] middle, double[] upper, double[] lower) {}

    private static double[] filled(int length) {
        double[] out = new double[length];
        java.util.Arrays.fill(out, Double.NaN);
        return out;
    }

    private static void requireSeries(double[] series) {
        if (series == null) {
            throw new IllegalArgumentException("Chuỗi giá không được null");
        }
    }

    private static void requireWindow(int window) {
        if (window < 2) {
            throw new IllegalArgumentException("Cửa sổ phải >= 2, nhận được " + window);
        }
    }
}
