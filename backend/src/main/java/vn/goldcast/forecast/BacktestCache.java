package vn.goldcast.forecast;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remembers backtest results for the exact inputs that produced them.
 *
 * <p>A rolling-origin backtest refits the model at every origin, which is the bulk of a
 * forecast request, while the inputs only change when ingest commits. The key holds a copy
 * of the whole series (and, for ARX_MACRO, of the aligned drivers) and compares them value
 * by value, so a revised quote or a new day is a different key: a cached answer is always
 * the answer the backtest would give now. Nothing ever needs to be invalidated by hand.
 *
 * <p>Bounded and least-recently-used. Concurrent misses on the same key may both compute;
 * the results are identical, so the duplicate work is the only cost.
 */
final class BacktestCache {

    private final int capacity;
    private final Map<Key, BacktestResult> entries;
    private long hits;
    private long misses;

    BacktestCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity phải >= 1");
        }
        this.capacity = capacity;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, BacktestResult> eldest) {
                return size() > BacktestCache.this.capacity;
            }
        };
    }

    /**
     * @param drivers aligned macro drivers the model reads, or {@code null} when it reads none
     */
    BacktestResult get(ForecastModel model, double[] series, double[][] drivers, int horizon,
                       int minTrain, int maxOrigins, Supplier<BacktestResult> compute) {
        Key key = new Key(model, horizon, minTrain, maxOrigins, series.clone(),
                drivers == null ? null : deepCopy(drivers));
        synchronized (this) {
            BacktestResult cached = entries.get(key);
            if (cached != null) {
                hits++;
                return cached;
            }
            misses++;
        }
        // Computed outside the lock: one slow fit must not hold up other series.
        BacktestResult result = compute.get();
        synchronized (this) {
            entries.put(key, result);
        }
        return result;
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long misses() {
        return misses;
    }

    synchronized int size() {
        return entries.size();
    }

    private static double[][] deepCopy(double[][] values) {
        double[][] copy = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i].clone();
        }
        return copy;
    }

    private record Key(ForecastModel model, int horizon, int minTrain, int maxOrigins,
                       double[] series, double[][] drivers) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && model == that.model
                    && horizon == that.horizon
                    && minTrain == that.minTrain
                    && maxOrigins == that.maxOrigins
                    && Arrays.equals(series, that.series)
                    && Arrays.deepEquals(drivers, that.drivers);
        }

        @Override
        public int hashCode() {
            int hash = model.hashCode();
            hash = 31 * hash + horizon;
            hash = 31 * hash + minTrain;
            hash = 31 * hash + maxOrigins;
            hash = 31 * hash + Arrays.hashCode(series);
            return 31 * hash + Arrays.deepHashCode(drivers);
        }
    }
}
