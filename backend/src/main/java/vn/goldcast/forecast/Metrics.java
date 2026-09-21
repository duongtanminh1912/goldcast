package vn.goldcast.forecast;

/**
 * Forecast accuracy on a set of (actual, predicted) pairs.
 *
 * <p>{@code mase} is the one worth reading: it divides mean absolute error by the mean
 * absolute error a naive "tomorrow equals today" forecast would have made on the same
 * series. Below 1 means the model adds information; at or above 1 it does not, whatever
 * its MAPE happens to look like.
 *
 * @param mae    mean absolute error, in the instrument's own unit
 * @param rmse   root mean squared error, punishes large misses
 * @param mape   mean absolute percentage error, in percent
 * @param mase   mean absolute scaled error; {@code NaN} when the naive scale is degenerate
 * @param sample number of (actual, predicted) pairs behind these numbers
 */
public record Metrics(double mae, double rmse, double mape, double mase, int sample) {

    public static final Metrics EMPTY = new Metrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

    /**
     * @param naiveScale mean absolute first difference of the training series; pass a
     *                   non-positive value when it cannot be computed, and MASE is NaN
     */
    public static Metrics of(double[] actual, double[] predicted, double naiveScale) {
        if (actual == null || predicted == null || actual.length != predicted.length) {
            throw new IllegalArgumentException("actual và predicted phải cùng độ dài và khác null");
        }
        int n = actual.length;
        if (n == 0) {
            return EMPTY;
        }

        double sumAbs = 0;
        double sumSq = 0;
        double sumPct = 0;
        int pctCount = 0;

        for (int i = 0; i < n; i++) {
            double error = actual[i] - predicted[i];
            sumAbs += Math.abs(error);
            sumSq += error * error;
            if (actual[i] != 0) {
                sumPct += Math.abs(error / actual[i]);
                pctCount++;
            }
        }

        double mae = sumAbs / n;
        double rmse = Math.sqrt(sumSq / n);
        double mape = pctCount == 0 ? Double.NaN : (sumPct / pctCount) * 100.0;
        double mase = naiveScale > 0 ? mae / naiveScale : Double.NaN;

        return new Metrics(mae, rmse, mape, mase, n);
    }

    /** Mean absolute first difference — the denominator MASE is scaled by. */
    public static double naiveScale(double[] series) {
        if (series == null || series.length < 2) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 1; i < series.length; i++) {
            sum += Math.abs(series[i] - series[i - 1]);
        }
        return sum / (series.length - 1);
    }

    /** True when this model carries information a naive forecast does not. */
    public boolean beatsNaive() {
        return Double.isFinite(mase) && mase < 1.0;
    }
}
