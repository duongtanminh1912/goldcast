package vn.goldcast.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MlForecasterTest {

    /** Prices from a return process where each return may depend on the previous one. */
    private static double[] pricesFromReturns(int n, long seed, ReturnRule rule) {
        Random random = new Random(seed);
        double[] prices = new double[n];
        prices[0] = 100;
        double previous = 0;
        for (int t = 1; t < n; t++) {
            double r = rule.next(previous, random);
            prices[t] = prices[t - 1] * Math.exp(r);
            previous = r;
        }
        return prices;
    }

    private interface ReturnRule {
        double next(double previous, Random random);
    }

    private static double[] randomWalk(int n, long seed) {
        return pricesFromReturns(n, seed, (prev, rnd) -> 0.01 * rnd.nextGaussian());
    }

    @Test
    @DisplayName("Đặc trưng tại t không phụ thuộc vào giá sau t (không rò rỉ tương lai)")
    void featuresDoNotLookAhead() {
        double[] prices = randomWalk(120, 1L);
        double[] before = MlFeatures.row(prices, 80);

        double[] tampered = prices.clone();
        for (int i = 81; i < tampered.length; i++) {
            tampered[i] *= 1.5;
        }
        assertArrayEquals(before, MlFeatures.row(tampered, 80), 0.0);
    }

    @Test
    @DisplayName("Tập huấn luyện: mỗi dòng đặc trưng tại t có nhãn là lợi suất t → t+1")
    void datasetTargetIsNextReturn() {
        double[] prices = randomWalk(60, 2L);
        MlFeatures.Dataset data = MlFeatures.dataset(prices);
        int t = MlFeatures.LOOKBACK;

        assertAll(
                () -> assertEquals(prices.length - 1 - MlFeatures.LOOKBACK, data.rows()),
                () -> assertEquals(Math.log(prices[t + 1] / prices[t]), data.y()[0], 1e-12),
                () -> assertArrayEquals(MlFeatures.row(prices, t), data.x()[0], 0.0));
    }

    @Test
    @DisplayName("Ridge học được quan hệ tự tương quan của lợi suất và đánh bại dự báo 'lợi suất = 0'")
    void ridgeLearnsReturnAutocorrelation() {
        double[] prices = pricesFromReturns(500, 3L,
                (prev, rnd) -> 0.6 * prev + 0.004 * rnd.nextGaussian());

        PointForecast forecast = new RidgeForecaster().forecast(prices, 5);
        Map<String, Double> params = forecast.params();

        double zeroForecastRmse = MlFeatures.returnStd(prices);
        assertAll(
                () -> assertTrue(params.get("beta.ret_lag1") > 0,
                        "Hệ số lợi suất trễ 1 phải dương, nhận được " + params.get("beta.ret_lag1")),
                () -> assertTrue(params.get("validRmse") < 0.9 * zeroForecastRmse,
                        "RMSE validation " + params.get("validRmse")
                                + " phải thấp hơn rõ so với " + zeroForecastRmse));
    }

    @Test
    @DisplayName("Gradient boosting học được quy luật phi tuyến mà mô hình tuyến tính bỏ sót")
    void gbmLearnsNonLinearRule() {
        // Next return depends on the *size* of today's move, not its sign: a pure
        // non-linearity with zero linear correlation for a regression to pick up.
        double[] prices = pricesFromReturns(700, 4L,
                (prev, rnd) -> (Math.abs(prev) > 0.01 ? 0.012 : -0.004) + 0.003 * rnd.nextGaussian());

        PointForecast gbm = new GradientBoostingForecaster().forecast(prices, 5);
        PointForecast ridge = new RidgeForecaster().forecast(prices, 5);

        double[] importance = Arrays.stream(MlFeatures.NAMES)
                .mapToDouble(name -> gbm.params().get("importance." + name))
                .toArray();
        int top = 0;
        for (int j = 1; j < importance.length; j++) {
            if (importance[j] > importance[top]) {
                top = j;
            }
        }
        final int topFeature = top;
        assertAll(
                () -> assertTrue(gbm.params().get("validRmse") < ridge.params().get("validRmse"),
                        "GBM " + gbm.params().get("validRmse") + " phải tốt hơn Ridge "
                                + ridge.params().get("validRmse") + " trên quy luật phi tuyến"),
                () -> assertEquals("ret_lag1", MlFeatures.NAMES[topFeature],
                        "Đặc trưng quan trọng nhất phải là lợi suất hôm nay"),
                () -> assertEquals(1.0, Arrays.stream(importance).sum(), 1e-9));
    }

    @Test
    @DisplayName("Gradient boosting cho kết quả giống hệt nhau giữa các lần chạy (seed cố định)")
    void gbmIsDeterministic() {
        double[] prices = randomWalk(300, 5L);
        double[] first = new GradientBoostingForecaster().forecast(prices, 10).values();
        double[] second = new GradientBoostingForecaster().forecast(prices, 10).values();
        assertArrayEquals(first, second, 0.0);
    }

    @Test
    @DisplayName("Mô hình ML luôn cho giá dương, hữu hạn và không bùng nổ ở tầm 90 ngày")
    void mlForecastsStayBounded() {
        double[] prices = randomWalk(400, 6L);
        double last = prices[prices.length - 1];
        for (Forecaster model : new Forecaster[] {new RidgeForecaster(), new GradientBoostingForecaster()}) {
            double[] values = model.forecast(prices, 90).values();
            assertEquals(90, values.length);
            assertTrue(Arrays.stream(values).allMatch(v -> Double.isFinite(v) && v > 0),
                    model.model() + " cho giá không hợp lệ: " + Arrays.toString(values));
            // 90 steps clipped at 3σ (σ ≈ 1%) cannot exceed e^(2.7) ≈ 15×; a sane model stays far inside.
            assertTrue(values[89] < last * 3 && values[89] > last / 3,
                    model.model() + " trôi quá xa: " + values[89] + " so với " + last);
        }
    }

    @Test
    @DisplayName("Chuỗi hằng số cho dự báo hằng số")
    void constantSeriesGivesConstantForecast() {
        double[] constant = new double[200];
        Arrays.fill(constant, 50.0);
        for (Forecaster model : new Forecaster[] {new RidgeForecaster(), new GradientBoostingForecaster()}) {
            double[] values = model.forecast(constant, 5).values();
            assertTrue(Arrays.stream(values).allMatch(v -> Math.abs(v - 50) < 1e-9),
                    model.model() + ": " + Arrays.toString(values));
        }
    }

    @Test
    @DisplayName("Mô hình ML từ chối chuỗi ngắn hơn yêu cầu tối thiểu")
    void mlModelsRejectShortSeries() {
        double[] shortSeries = randomWalk(40, 7L);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RidgeForecaster().forecast(shortSeries, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new GradientBoostingForecaster().forecast(shortSeries, 3)));
    }

    @Test
    @DisplayName("Backtest rolling-origin chạy được với mô hình ML và nằm trong danh sách ứng viên AUTO")
    void mlModelsAreBacktestedAndAutoCandidates() {
        double[] prices = randomWalk(300, 8L);
        BacktestResult result = new Backtester(30).run(prices, new GradientBoostingForecaster(), 5, 150);

        assertAll(
                () -> assertTrue(result.hasData(), "Backtest GBM phải có dữ liệu"),
                () -> assertTrue(Double.isFinite(result.overall().mase())),
                () -> assertTrue(Forecasters.candidates(prices.length, 5).stream()
                        .anyMatch(f -> f.model() == ForecastModel.RIDGE)),
                () -> assertTrue(Forecasters.candidates(prices.length, 5).stream()
                        .anyMatch(f -> f.model() == ForecastModel.GBM)));
    }
}
