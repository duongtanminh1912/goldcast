package vn.goldcast.forecast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AR(p) on first differences plus lagged changes of exogenous drivers — an ARIMAX(p,1,0)
 * fitted by ordinary least squares.
 *
 * <pre>
 *   d(t)    = y(t) − y(t−1)
 *   e_k(t)  = x_k(t) − x_k(t−1)
 *   d(t)    = c + Σ_i φ_i·d(t−i) + Σ_k Σ_j β_kj·e_k(t−j) + ε(t),   i = 1..p, j = 1..q
 * </pre>
 *
 * <p>Every regressor is lagged by at least one row, so the change on day t is explained
 * only by information that existed before day t. The drivers are supplied already
 * aligned to the price rows ({@code drivers[k][row]}); the forecaster reads only rows
 * {@code 0..series.length-1}, so passing a prefix of the price series — as the
 * {@link Backtester} does — never exposes later driver values.
 *
 * <p>Multi-step forecasts are recursive. Future driver changes are unknown and are set
 * to zero, i.e. the drivers are assumed to stay where they last were. That assumption is
 * the honest one: forecasting the Fed is out of scope.
 *
 * <p>Regressors are standardised before fitting, so the tiny ridge term in
 * {@link LinearAlgebra#solveOls} does not shrink a yield change (~0.05) far more than a
 * gold price change (~20). Reported coefficients are converted back to original units.
 */
public final class ArxMacroForecaster implements Forecaster {

    private final double[][] drivers;
    private final String[] driverNames;
    private final int order;
    private final int driverLags;

    /**
     * @param drivers     one row-aligned array per driver; {@code NaN} where no value is known
     * @param driverNames parameter name prefix per driver, e.g. {@code realYield10y}
     * @param order       p, lags of the price change
     * @param driverLags  q, lags of each driver change
     */
    public ArxMacroForecaster(double[][] drivers, String[] driverNames, int order, int driverLags) {
        if (drivers == null || drivers.length == 0 || driverNames == null
                || driverNames.length != drivers.length) {
            throw new IllegalArgumentException("Cần ít nhất một biến giải thích và tên tương ứng");
        }
        if (order < 1 || order > 10 || driverLags < 1 || driverLags > 10) {
            throw new IllegalArgumentException("Bậc trễ phải nằm trong 1..10");
        }
        this.drivers = drivers;
        this.driverNames = driverNames.clone();
        this.order = order;
        this.driverLags = driverLags;
    }

    @Override
    public ForecastModel model() {
        return ForecastModel.ARX_MACRO;
    }

    private int parameterCount() {
        return 1 + order + drivers.length * driverLags;
    }

    @Override
    public int minObservations() {
        return 3 * parameterCount() + 12 + Math.max(order, driverLags) + 1;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);
        int n = series.length;
        for (double[] driver : drivers) {
            if (driver.length < n) {
                throw new IllegalArgumentException("Dữ liệu vĩ mô ngắn hơn chuỗi giá");
            }
        }

        // d[t] for t = 1..n-1 (d[0] unused); e[k][t] likewise.
        double[] d = new double[n];
        double[][] e = new double[drivers.length][n];
        for (int t = 1; t < n; t++) {
            d[t] = series[t] - series[t - 1];
            for (int k = 0; k < drivers.length; k++) {
                e[k][t] = drivers[k][t] - drivers[k][t - 1];
            }
        }
        for (int k = 0; k < drivers.length; k++) {
            e[k][0] = Double.NaN;
        }

        int columns = parameterCount();
        int firstRow = Math.max(order, driverLags) + 1;
        double[][] rows = new double[Math.max(0, n - firstRow)][];
        double[] targets = new double[rows.length];
        int used = 0;
        for (int t = firstRow; t < n; t++) {
            double[] row = regressors(d, e, t);
            if (row != null) {
                rows[used] = row;
                targets[used] = d[t];
                used++;
            }
        }
        if (used < 3 * columns + 12) {
            throw new IllegalArgumentException(
                    "ARX_MACRO cần ít nhất " + (3 * columns + 12) + " ngày có đủ dữ liệu vĩ mô, có " + used);
        }

        double[] scale = new double[columns];
        scale[0] = 1.0;
        for (int c = 1; c < columns; c++) {
            double sum = 0;
            double sumSq = 0;
            for (int r = 0; r < used; r++) {
                sum += rows[r][c];
                sumSq += rows[r][c] * rows[r][c];
            }
            double mean = sum / used;
            double sd = Math.sqrt(Math.max(sumSq / used - mean * mean, 0));
            scale[c] = sd > 1e-12 ? sd : 1.0;
        }
        double[][] design = new double[used][columns];
        double[] target = new double[used];
        for (int r = 0; r < used; r++) {
            for (int c = 0; c < columns; c++) {
                design[r][c] = rows[r][c] / scale[c];
            }
            target[r] = targets[r];
        }

        double[] standardised = LinearAlgebra.solveOls(design, target);
        if (standardised == null) {
            return new DriftForecaster().forecast(series, horizon);
        }
        double[] beta = new double[columns];
        for (int c = 0; c < columns; c++) {
            beta[c] = standardised[c] / scale[c];
        }

        // Extend d and e into the future. Future driver changes are assumed to be zero.
        double[] dExt = new double[n + horizon];
        System.arraycopy(d, 0, dExt, 0, n);
        double[][] eExt = new double[drivers.length][n + horizon];
        for (int k = 0; k < drivers.length; k++) {
            System.arraycopy(e[k], 0, eExt[k], 0, n);
        }

        double level = series[n - 1];
        double[] out = new double[horizon];
        for (int h = 0; h < horizon; h++) {
            int t = n + h;
            double[] row = regressors(dExt, eExt, t);
            if (row == null) {
                throw new IllegalArgumentException("Thiếu dữ liệu vĩ mô gần nhất để dự báo");
            }
            double next = 0;
            for (int c = 0; c < columns; c++) {
                next += beta[c] * row[c];
            }
            if (!Double.isFinite(next)) {
                return new DriftForecaster().forecast(series, horizon);
            }
            dExt[t] = next;
            level = Math.max(level + next, 1e-9);
            out[h] = level;
        }

        double sse = 0;
        for (int r = 0; r < used; r++) {
            double fitted = 0;
            for (int c = 0; c < columns; c++) {
                fitted += beta[c] * rows[r][c];
            }
            double error = targets[r] - fitted;
            sse += error * error;
        }

        Map<String, Double> params = new LinkedHashMap<>();
        params.put("order", (double) order);
        params.put("driverLags", (double) driverLags);
        params.put("intercept", beta[0]);
        for (int i = 1; i <= order; i++) {
            params.put("phi" + i, beta[i]);
        }
        int c = 1 + order;
        for (int k = 0; k < drivers.length; k++) {
            for (int j = 1; j <= driverLags; j++) {
                params.put(driverNames[k] + "_lag" + j, beta[c++]);
            }
        }
        params.put("trainRows", (double) used);
        params.put("trainRmse", Math.sqrt(sse / Math.max(1, used)));
        return new PointForecast(model(), out, params);
    }

    /** Regressor row for target index t, or {@code null} if any input is missing. */
    private double[] regressors(double[] d, double[][] e, int t) {
        double[] row = new double[parameterCount()];
        row[0] = 1.0;
        int c = 1;
        for (int i = 1; i <= order; i++) {
            int index = t - i;
            if (index < 1) {
                return null;
            }
            row[c++] = d[index];
        }
        for (double[] driverChanges : e) {
            for (int j = 1; j <= driverLags; j++) {
                int index = t - j;
                double value = index < 1 ? Double.NaN : driverChanges[index];
                if (!Double.isFinite(value)) {
                    return null;
                }
                row[c++] = value;
            }
        }
        return row;
    }
}
