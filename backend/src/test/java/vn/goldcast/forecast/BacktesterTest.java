package vn.goldcast.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktesterTest {

    private static double[] linear(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = 100 + i;
        }
        return out;
    }

    @Test
    @DisplayName("Trên chuỗi tuyến tính hoàn hảo, Drift có sai số bằng 0 còn Naive thì không")
    void driftBeatsNaiveOnPerfectTrend() {
        double[] series = linear(120);
        Backtester backtester = new Backtester();

        BacktestResult naive = backtester.run(series, new NaiveForecaster(), 3, 10);
        BacktestResult drift = backtester.run(series, new DriftForecaster(), 3, 10);

        assertAll(
                () -> assertTrue(naive.hasData()),
                () -> assertTrue(drift.hasData()),
                () -> assertEquals(0.0, drift.overall().mae(), 1e-6),
                () -> assertTrue(drift.overall().mae() < naive.overall().mae()));
    }

    @Test
    @DisplayName("Sai số tăng theo số bước dự báo")
    void errorGrowsWithHorizon() {
        BacktestResult result = new Backtester().run(linear(120), new NaiveForecaster(), 5, 10);
        assertAll(
                () -> assertEquals(5, result.byHorizon().size()),
                () -> assertTrue(result.rmseAt(5) > result.rmseAt(1),
                        "RMSE ở bước 5 phải lớn hơn bước 1"));
    }

    @Test
    @DisplayName("Chuỗi quá ngắn trả về kết quả rỗng thay vì ném lỗi")
    void tooShortSeriesReturnsEmpty() {
        BacktestResult result = new Backtester().run(new double[] {1, 2, 3}, new HoltDampedForecaster(), 5, 30);
        assertAll(
                () -> assertFalse(result.hasData()),
                () -> assertEquals(0, result.origins()),
                () -> assertTrue(result.byHorizon().isEmpty()),
                () -> assertTrue(Double.isNaN(result.rmseAt(1))),
                () -> assertEquals(0, result.sampleAt(1)));
    }

    @Test
    @DisplayName("Số điểm gốc bị giới hạn bởi maxOrigins")
    void originCountIsCapped() {
        BacktestResult result = new Backtester(10).run(linear(500), new NaiveForecaster(), 3, 10);
        assertTrue(result.origins() <= 10, "Nhận được " + result.origins() + " điểm gốc");
        assertTrue(result.origins() > 0);
    }

    @Test
    @DisplayName("Cửa sổ huấn luyện tối thiểu được nâng lên theo yêu cầu của mô hình")
    void minTrainRespectsModelRequirement() {
        BacktestResult result = new Backtester().run(linear(200), new ArDiffForecaster(5), 3, 5);
        assertTrue(result.minTrain() >= new ArDiffForecaster(5).minObservations(),
                "minTrain=" + result.minTrain());
    }

    @Test
    @DisplayName("Mỗi bước dự báo đều có đủ mẫu quan sát")
    void everyHorizonHasSamples() {
        BacktestResult result = new Backtester().run(linear(150), new NaiveForecaster(), 4, 20);
        for (int step = 1; step <= 4; step++) {
            assertTrue(result.sampleAt(step) > 0, "Bước " + step + " không có mẫu nào");
        }
    }

    @Test
    @DisplayName("Đầu vào không hợp lệ không làm hỏng backtest")
    void invalidInputIsTolerated() {
        assertFalse(new Backtester().run(null, new NaiveForecaster(), 3, 10).hasData());
        assertFalse(new Backtester().run(linear(50), new NaiveForecaster(), 0, 10).hasData());
    }
}
