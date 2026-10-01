package vn.goldcast.forecast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gradient-boosted regression trees on engineered market features — the non-linear
 * machine-learning model.
 *
 * <pre>
 *   F₀(x)  = mean(r)
 *   Fₘ(x)  = Fₘ₋₁(x) + η · treeₘ(x),   treeₘ fitted to the residuals r − Fₘ₋₁(x)
 * </pre>
 *
 * <p>This is the squared-error gradient boosting of Friedman (2001), the same algorithm
 * family as XGBoost and LightGBM, written out in full because the project keeps its
 * maths dependency-free. It borrows LightGBM's main speed trick: each feature is cut
 * into at most {@value #BINS} quantile bins once per fit, so finding a split is a pass
 * over a histogram rather than a sort. That is what makes refitting the model at every
 * backtest origin affordable.
 *
 * <p>Regularisation, all standard: shallow trees, shrinkage η, row subsampling
 * (stochastic gradient boosting) and a minimum leaf size. The number of trees is
 * chosen by early stopping on the most recent {@value #VALIDATION_SHARE} of rows, then
 * the model is refitted on everything with that count. Randomness comes from a fixed
 * seed, so the same history always gives the same forecast.
 */
public final class GradientBoostingForecaster implements Forecaster {

    private static final int MAX_TREES = 200;
    private static final int MAX_DEPTH = 3;
    private static final double LEARNING_RATE = 0.05;
    private static final double SUBSAMPLE = 0.8;
    private static final int MIN_LEAF = 15;
    private static final int BINS = 32;
    private static final int PATIENCE = 25;
    private static final double VALIDATION_SHARE = 0.2;
    private static final long SEED = 20_240_101L;

    @Override
    public ForecastModel model() {
        return ForecastModel.GBM;
    }

    @Override
    public int minObservations() {
        // Trees need more rows than a linear model before a leaf of MIN_LEAF means anything.
        return MlFeatures.LOOKBACK + 100;
    }

    @Override
    public PointForecast forecast(double[] series, int horizon) {
        validate(series, horizon);

        MlFeatures.Dataset all = MlFeatures.dataset(series);
        int trainRows = (int) Math.round(all.rows() * (1 - VALIDATION_SHARE));

        // Pass 1: grow on the older rows, watch the newer ones, remember when they stopped improving.
        Ensemble probe = Ensemble.fit(all.head(trainRows), MAX_TREES, all.tail(trainRows));
        int trees = Math.max(1, probe.bestIteration);

        // Pass 2: the final model sees every row, stopped at the count validation picked.
        Ensemble model = Ensemble.fit(all, trees, null);

        double[] values = MlFeatures.recursive(series, horizon, model::predict);

        Map<String, Double> params = new LinkedHashMap<>();
        params.put("trees", (double) trees);
        params.put("learningRate", LEARNING_RATE);
        params.put("maxDepth", (double) MAX_DEPTH);
        params.put("validRmse", Math.sqrt(probe.bestValidMse));
        params.put("trainRows", (double) all.rows());
        double[] importance = model.importance();
        for (int j = 0; j < MlFeatures.COUNT; j++) {
            params.put("importance." + MlFeatures.NAMES[j], importance[j]);
        }
        return new PointForecast(model(), values, params);
    }

    /** A boosted sum of trees plus the bookkeeping early stopping needs. */
    private static final class Ensemble {
        final double base;
        final List<Node> trees;
        final double[] gain;
        final int bestIteration;
        final double bestValidMse;

        private Ensemble(double base, List<Node> trees, double[] gain, int bestIteration,
                         double bestValidMse) {
            this.base = base;
            this.trees = trees;
            this.gain = gain;
            this.bestIteration = bestIteration;
            this.bestValidMse = bestValidMse;
        }

        static Ensemble fit(MlFeatures.Dataset data, int maxTrees, MlFeatures.Dataset valid) {
            int n = data.rows();
            int p = MlFeatures.COUNT;
            double[] y = data.y();

            double base = 0;
            for (double v : y) {
                base += v;
            }
            base /= Math.max(1, n);

            double[][] thresholds = new double[p][];
            for (int j = 0; j < p; j++) {
                thresholds[j] = quantileThresholds(data.x(), j);
            }
            // bins[i][j]: which bin row i falls into for feature j; split k sends bins <= k left.
            byte[][] bins = new byte[n][p];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < p; j++) {
                    bins[i][j] = (byte) binOf(thresholds[j], data.x()[i][j]);
                }
            }

            double[] fitted = new double[n];
            Arrays.fill(fitted, base);
            double[] validFitted = null;
            if (valid != null) {
                validFitted = new double[valid.rows()];
                Arrays.fill(validFitted, base);
            }

            Random random = new Random(SEED);
            double[] gain = new double[p];
            List<Node> trees = new ArrayList<>();
            double[] residual = new double[n];

            int bestIteration = 0;
            double bestValidMse = valid == null ? Double.NaN : mse(valid.y(), validFitted);
            int sinceBest = 0;

            for (int m = 0; m < maxTrees; m++) {
                for (int i = 0; i < n; i++) {
                    residual[i] = y[i] - fitted[i];
                }
                int[] sample = subsample(n, random);
                Node tree = grow(sample, residual, bins, thresholds, 0, gain);
                trees.add(tree);

                for (int i = 0; i < n; i++) {
                    fitted[i] += LEARNING_RATE * tree.predict(data.x()[i]);
                }

                if (valid != null) {
                    for (int i = 0; i < valid.rows(); i++) {
                        validFitted[i] += LEARNING_RATE * tree.predict(valid.x()[i]);
                    }
                    double validMse = mse(valid.y(), validFitted);
                    if (validMse < bestValidMse - 1e-15) {
                        bestValidMse = validMse;
                        bestIteration = m + 1;
                        sinceBest = 0;
                    } else if (++sinceBest >= PATIENCE) {
                        break;
                    }
                }
            }
            if (valid == null) {
                bestIteration = trees.size();
            }
            return new Ensemble(base, trees, gain, bestIteration, bestValidMse);
        }

        double predict(double[] features) {
            double out = base;
            for (Node tree : trees) {
                out += LEARNING_RATE * tree.predict(features);
            }
            return out;
        }

        /** Share of total squared-error reduction attributed to each feature; sums to 1. */
        double[] importance() {
            double total = 0;
            for (double g : gain) {
                total += g;
            }
            double[] out = new double[gain.length];
            if (total > 0) {
                for (int j = 0; j < gain.length; j++) {
                    out[j] = gain[j] / total;
                }
            }
            return out;
        }
    }

    /** Either a leaf (feature &lt; 0) or a binary split on {@code feature <= threshold}. */
    private record Node(int feature, double threshold, Node left, Node right, double value) {

        static Node leaf(double value) {
            return new Node(-1, 0, null, null, value);
        }

        double predict(double[] x) {
            Node node = this;
            while (node.feature >= 0) {
                node = x[node.feature] <= node.threshold ? node.left : node.right;
            }
            return node.value;
        }
    }

    private static Node grow(int[] rows, double[] residual, byte[][] bins, double[][] thresholds,
                             int depth, double[] gain) {
        double sum = 0;
        for (int r : rows) {
            sum += residual[r];
        }
        double mean = sum / rows.length;
        if (depth >= MAX_DEPTH || rows.length < 2 * MIN_LEAF) {
            return Node.leaf(mean);
        }

        double parentScore = sum * sum / rows.length;
        double bestGain = 0;
        int bestFeature = -1;
        int bestBin = -1;

        double[] binSum = new double[BINS];
        int[] binCount = new int[BINS];
        for (int j = 0; j < thresholds.length; j++) {
            int cuts = thresholds[j].length;
            if (cuts == 0) {
                continue;
            }
            Arrays.fill(binSum, 0);
            Arrays.fill(binCount, 0);
            for (int r : rows) {
                int b = bins[r][j];
                binSum[b] += residual[r];
                binCount[b]++;
            }

            double leftSum = 0;
            int leftCount = 0;
            for (int k = 0; k < cuts; k++) {
                leftSum += binSum[k];
                leftCount += binCount[k];
                int rightCount = rows.length - leftCount;
                if (leftCount < MIN_LEAF) {
                    continue;
                }
                if (rightCount < MIN_LEAF) {
                    break;
                }
                double rightSum = sum - leftSum;
                // Reduction in squared error from splitting the node's mean into two means.
                double g = leftSum * leftSum / leftCount + rightSum * rightSum / rightCount - parentScore;
                if (g > bestGain) {
                    bestGain = g;
                    bestFeature = j;
                    bestBin = k;
                }
            }
        }

        if (bestFeature < 0) {
            return Node.leaf(mean);
        }
        gain[bestFeature] += bestGain;

        int leftSize = 0;
        for (int r : rows) {
            if (bins[r][bestFeature] <= bestBin) {
                leftSize++;
            }
        }
        int[] left = new int[leftSize];
        int[] right = new int[rows.length - leftSize];
        int li = 0;
        int ri = 0;
        for (int r : rows) {
            if (bins[r][bestFeature] <= bestBin) {
                left[li++] = r;
            } else {
                right[ri++] = r;
            }
        }

        return new Node(bestFeature, thresholds[bestFeature][bestBin],
                grow(left, residual, bins, thresholds, depth + 1, gain),
                grow(right, residual, bins, thresholds, depth + 1, gain),
                mean);
    }

    /** Up to BINS−1 distinct cut points at evenly spaced quantiles of one feature. */
    private static double[] quantileThresholds(double[][] x, int feature) {
        double[] column = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            column[i] = x[i][feature];
        }
        Arrays.sort(column);
        double[] cuts = new double[BINS - 1];
        int count = 0;
        for (int k = 1; k < BINS; k++) {
            double q = column[Math.min(column.length - 1, (int) ((long) k * column.length / BINS))];
            if (count == 0 || q > cuts[count - 1]) {
                cuts[count++] = q;
            }
        }
        // The largest cut would send everything left; it can never be a useful split.
        if (count > 0 && cuts[count - 1] >= column[column.length - 1]) {
            count--;
        }
        return Arrays.copyOf(cuts, count);
    }

    /** Index of the first threshold >= value, i.e. the bin whose split would send it left. */
    private static int binOf(double[] thresholds, double value) {
        int lo = 0;
        int hi = thresholds.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (value <= thresholds[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    private static int[] subsample(int n, Random random) {
        int size = Math.max(2 * MIN_LEAF, (int) Math.round(n * SUBSAMPLE));
        if (size >= n) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) {
                all[i] = i;
            }
            return all;
        }
        // Partial Fisher–Yates: the first `size` entries are a uniform sample without replacement.
        int[] index = new int[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        for (int i = 0; i < size; i++) {
            int j = i + random.nextInt(n - i);
            int tmp = index[i];
            index[i] = index[j];
            index[j] = tmp;
        }
        return Arrays.copyOf(index, size);
    }

    private static double mse(double[] actual, double[] predicted) {
        if (actual.length == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double ss = 0;
        for (int i = 0; i < actual.length; i++) {
            double e = actual[i] - predicted[i];
            ss += e * e;
        }
        return ss / actual.length;
    }
}
