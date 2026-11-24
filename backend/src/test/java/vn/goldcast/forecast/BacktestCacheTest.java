package vn.goldcast.forecast;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BacktestCacheTest {

    private final AtomicInteger computed = new AtomicInteger();

    private BacktestResult compute() {
        computed.incrementAndGet();
        return BacktestResult.empty(ForecastModel.NAIVE, 3);
    }

    @Test
    void differentMacroDriversAreDifferentBacktests() {
        BacktestCache cache = new BacktestCache(8);
        double[] closes = {1, 2, 3, 4};
        cache.get(ForecastModel.ARX_MACRO, closes, new double[][] {{0.1, 0.2, 0.3, 0.4}}, 3, 2, 10, this::compute);
        cache.get(ForecastModel.ARX_MACRO, closes, new double[][] {{0.1, 0.2, 0.3, 0.5}}, 3, 2, 10, this::compute);
        cache.get(ForecastModel.ARX_MACRO, closes, new double[][] {{0.1, 0.2, 0.3, 0.5}}, 3, 2, 10, this::compute);
        assertEquals(2, computed.get());
    }

    @Test
    void theKeyIsACopySoLaterChangesToTheCallersArrayCannotPoisonIt() {
        BacktestCache cache = new BacktestCache(8);
        double[] closes = {1, 2, 3, 4};
        cache.get(ForecastModel.NAIVE, closes, null, 3, 2, 10, this::compute);
        closes[3] = 99;
        cache.get(ForecastModel.NAIVE, closes, null, 3, 2, 10, this::compute);
        cache.get(ForecastModel.NAIVE, new double[] {1, 2, 3, 4}, null, 3, 2, 10, this::compute);
        assertEquals(2, computed.get());
    }

    @Test
    void everySettingThatChangesTheBacktestIsPartOfTheKey() {
        BacktestCache cache = new BacktestCache(16);
        double[] closes = {1, 2, 3, 4};
        cache.get(ForecastModel.NAIVE, closes, null, 3, 2, 10, this::compute);
        cache.get(ForecastModel.DRIFT, closes, null, 3, 2, 10, this::compute);
        cache.get(ForecastModel.NAIVE, closes, null, 4, 2, 10, this::compute);
        cache.get(ForecastModel.NAIVE, closes, null, 3, 1, 10, this::compute);
        cache.get(ForecastModel.NAIVE, closes, null, 3, 2, 20, this::compute);
        assertEquals(5, computed.get());
    }

    @Test
    void leastRecentlyUsedEntryIsDroppedAtCapacity() {
        BacktestCache cache = new BacktestCache(2);
        double[] a = {1}, b = {2}, c = {3};
        cache.get(ForecastModel.NAIVE, a, null, 1, 1, 1, this::compute);
        cache.get(ForecastModel.NAIVE, b, null, 1, 1, 1, this::compute);
        cache.get(ForecastModel.NAIVE, a, null, 1, 1, 1, this::compute); // a vua dung
        cache.get(ForecastModel.NAIVE, c, null, 1, 1, 1, this::compute); // day b ra
        assertEquals(2, cache.size());
        cache.get(ForecastModel.NAIVE, a, null, 1, 1, 1, this::compute);
        assertEquals(3, computed.get());
        cache.get(ForecastModel.NAIVE, b, null, 1, 1, 1, this::compute);
        assertEquals(4, computed.get());
    }
}
