package vn.goldcast.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntervalEstimatorTest {

    private static BacktestResult naiveBacktestOnLinearSeries() {
        double[] series = new double[150];
        for (int i = 0; i < series.length; i++) {
            series[i] = 100 + i;
        }
        return new Backtester().run(series, new NaiveForecaster(), 5, 20);
    }

    @Test
    @DisplayName("Khoảng 95% rộng hơn khoảng 80%")
    void wider95ThanEighty() {
        PredictionInterval interval = IntervalEstimator.estimate(100, 1, naiveBacktestOnLinearSeries(), 1.0);
        assertTrue(interval.upper95() - interval.lower95() > interval.upper80() - interval.lower80());
    }

    @Test
    @DisplayName("Khoảng tin cậy luôn bao quanh giá trị điểm")
    void intervalBracketsThePointForecast() {
        PredictionInterval interval = IntervalEstimator.estimate(100, 1, naiveBacktestOnLinearSeries(), 1.0);
        assertAll(
                () -> assertTrue(interval.lower95() <= 100),
                () -> assertTrue(interval.upper95() >= 100),
                () -> assertTrue(interval.lower80() <= 100),
                () -> assertTrue(interval.upper80() >= 100));
    }

    @Test
    @DisplayName("Khoảng tin cậy nở ra theo số bước dự báo")
    void intervalWidensWithHorizon() {
        BacktestResult backtest = naiveBacktestOnLinearSeries();
        PredictionInterval near = IntervalEstimator.estimate(100, 1, backtest, 1.0);
        PredictionInterval far = IntervalEstimator.estimate(100, 5, backtest, 1.0);

        assertTrue(far.upper95() - far.lower95() > near.upper95() - near.lower95(),
                "Bước 5 phải rộng hơn bước 1");
    }

    @Test
    @DisplayName("Cận dưới không bao giờ âm — giá không thể nhỏ hơn 0")
    void lowerBoundNeverNegative() {
        PredictionInterval interval = IntervalEstimator.estimate(1.0, 5, null, 100.0);
        assertAll(
                () -> assertTrue(interval.lower80() >= 0),
                () -> assertTrue(interval.lower95() >= 0));
    }

    @Test
    @DisplayName("Không có backtest thì dùng sigma dự phòng nhân căn bậc hai của bước")
    void fallsBackToScaledSigma() {
        PredictionInterval step1 = IntervalEstimator.estimate(1000, 1, null, 10.0);
        PredictionInterval step4 = IntervalEstimator.estimate(1000, 4, null, 10.0);

        double width1 = step1.upper95() - step1.lower95();
        double width4 = step4.upper95() - step4.lower95();
        assertEquals(2.0, width4 / width1, 1e-6, "Bước 4 phải rộng gấp căn(4) = 2 lần bước 1");
    }

    @Test
    @DisplayName("Không ước lượng được sigma thì khoảng co về đúng giá trị điểm")
    void collapsesWhenSigmaUnknown() {
        PredictionInterval interval = IntervalEstimator.estimate(500, 1, null, Double.NaN);
        assertAll(
                () -> assertEquals(500, interval.lower95(), 1e-9),
                () -> assertEquals(500, interval.upper95(), 1e-9));
    }

    @Test
    @DisplayName("diffSigma bằng 0 trên chuỗi tăng đều tuyệt đối")
    void diffSigmaIsZeroForConstantSteps() {
        double[] series = new double[50];
        for (int i = 0; i < series.length; i++) {
            series[i] = 100 + i;
        }
        assertEquals(0.0, IntervalEstimator.diffSigma(series), 1e-9);
    }

    @Test
    @DisplayName("diffSigma cần tối thiểu ba quan sát")
    void diffSigmaNeedsEnoughData() {
        assertTrue(Double.isNaN(IntervalEstimator.diffSigma(new double[] {1, 2})));
        assertTrue(Double.isNaN(IntervalEstimator.diffSigma(null)));
    }
}
