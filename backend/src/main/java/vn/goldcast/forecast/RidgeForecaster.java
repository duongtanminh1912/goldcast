package vn.goldcast.forecast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ridge regression on engineered market features — the linear machine-learning model.
 *
 * <pre>
 *   r(t+1) = β₀ + Σ βⱼ · zⱼ(t) + ε,   minimise  Σ ε² + λ · Σ βⱼ²
 * </pre>
 *
 * <p>where zⱼ are the {@link MlFeatures} standardised to zero mean and unit variance on
 * the training rows, so a single λ penalises every coefficient on the same scale. The
 * intercept is not penalised.
 *
 * <p>λ is a hyperparameter, so it is chosen the way ML practice says it must be: on data
 * the fit did not see. The most recent {@value #VALIDATION_SHARE} of training rows is
 * held out, each λ on the grid is fitted on the rest and scored on that block, and the
 * winner is refitted on all rows. The hold-out is chronological, never shuffled — a
 * random split would let the model train on days after the ones it is graded on.
 */
public final class RidgeForecaster implements Forecaster {

    private static final double[] LAMBDA_GRID = {0.1, 1, 10, 100, 1_000, 10_000};
    private static final double VALIDATION_SHARE = 0.2;

    @Override
    public ForecastModel model() {
        return ForecastModel.RIDGE;
    }

    @Override
    public int minObservations() {
        // Lookback plus enough rows that a 20% validation block still has a dozen points.
        return MlFeatures.LOOKBACK + 60;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);

        MlFeatures.Dataset all = MlFeatures.dataset(series);
        int trainRows = (int) Math.round(all.rows() * (1 - VALIDATION_SHARE));
        MlFeatures.Dataset train = all.head(trainRows);
        MlFeatures.Dataset valid = all.tail(trainRows);

        double bestLambda = LAMBDA_GRID[LAMBDA_GRID.length - 1];
        double bestMse = Double.POSITIVE_INFINITY;
        for (double lambda : LAMBDA_GRID) {
            Fit fit = Fit.of(train, lambda);
            if (fit == null) {
                continue;
            }
            double mse = fit.mse(valid);
            if (mse < bestMse) {
                bestMse = mse;
                bestLambda = lambda;
            }
        }

        Fit fit = Fit.of(all, bestLambda);
        if (fit == null) {
            return new DriftForecaster().forecast(series, horizon);
        }

        double[] values = MlFeatures.recursive(series, horizon, fit::predict);

        Map<String, Double> params = new LinkedHashMap<>();
        params.put("lambda", bestLambda);
        params.put("validRmse", Math.sqrt(bestMse));
        params.put("trainRows", (double) all.rows());
        params.put("intercept", fit.intercept);
        for (int j = 0; j < MlFeatures.COUNT; j++) {
            params.put("beta." + MlFeatures.NAMES[j], fit.beta[j]);
        }
        return new PointForecast(model(), values, params);
    }

    /** Standardisation statistics and coefficients from one ridge solve. */
    private static final class Fit {
        final double[] mean;
        final double[] scale;
        final double[] beta;
        final double intercept;

        private Fit(double[] mean, double[] scale, double[] beta, double intercept) {
            this.mean = mean;
            this.scale = scale;
            this.beta = beta;
            this.intercept = intercept;
        }

        static Fit of(MlFeatures.Dataset data, double lambda) {
            int n = data.rows();
            int p = MlFeatures.COUNT;
            if (n < 2) {
                return null;
            }

            double[] mean = new double[p];
            double[] scale = new double[p];
            for (double[] row : data.x()) {
                for (int j = 0; j < p; j++) {
                    mean[j] += row[j];
                }
            }
            for (int j = 0; j < p; j++) {
                mean[j] /= n;
            }
            for (double[] row : data.x()) {
                for (int j = 0; j < p; j++) {
                    double d = row[j] - mean[j];
                    scale[j] += d * d;
                }
            }
            for (int j = 0; j < p; j++) {
                scale[j] = Math.sqrt(scale[j] / n);
                // A constant feature carries no information; leave it at zero instead of dividing by 0.
                if (!(scale[j] > 1e-12)) {
                    scale[j] = 1;
                }
            }

            double yMean = 0;
            for (double v : data.y()) {
                yMean += v;
            }
            yMean /= n;

            // Centring both sides removes the intercept from the penalised system.
            double[][] z = new double[n][p];
            double[] yc = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < p; j++) {
                    z[i][j] = (data.x()[i][j] - mean[j]) / scale[j];
                }
                yc[i] = data.y()[i] - yMean;
            }

            double[] beta = LinearAlgebra.solveRidge(z, yc, lambda);
            if (beta == null) {
                return null;
            }
            return new Fit(mean, scale, beta, yMean);
        }

        double predict(double[] features) {
            double out = intercept;
            for (int j = 0; j < beta.length; j++) {
                out += beta[j] * (features[j] - mean[j]) / scale[j];
            }
            return out;
        }

        double mse(MlFeatures.Dataset data) {
            if (data.rows() == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double ss = 0;
            for (int i = 0; i < data.rows(); i++) {
                double e = data.y()[i] - predict(data.x()[i]);
                ss += e * e;
            }
            return ss / data.rows();
        }
    }
}
