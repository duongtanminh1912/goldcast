package vn.goldcast.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    private static final double[] ACTUAL = {10, 20, 30};
    private static final double[] PREDICTED = {12, 18, 33};

    @Test
    @DisplayName("MAE, RMSE và MAPE khớp với tính tay")
    void computesStandardErrorMeasures() {
        Metrics metrics = Metrics.of(ACTUAL, PREDICTED, 2.0);
        assertAll(
                () -> assertEquals((2 + 2 + 3) / 3.0, metrics.mae(), 1e-9),
                () -> assertEquals(Math.sqrt((4 + 4 + 9) / 3.0), metrics.rmse(), 1e-9),
                () -> assertEquals((0.2 + 0.1 + 0.1) / 3.0 * 100, metrics.mape(), 1e-9),
                () -> assertEquals(3, metrics.sample()));
    }

    @Test
    @DisplayName("MASE chia MAE cho sai số của dự báo naive")
    void masesScalesAgainstNaive() {
        assertEquals((7 / 3.0) / 2.0, Metrics.of(ACTUAL, PREDICTED, 2.0).mase(), 1e-9);
    }

    @Test
    @DisplayName("MASE là NaN khi không tính được thang naive")
    void maseIsNaNWithoutScale() {
        assertTrue(Double.isNaN(Metrics.of(ACTUAL, PREDICTED, 0).mase()));
        assertTrue(Double.isNaN(Metrics.of(ACTUAL, PREDICTED, Double.NaN).mase()));
    }

    @Test
    @DisplayName("naiveScale là trung bình trị tuyệt đối sai phân bậc 1")
    void naiveScaleIsMeanAbsoluteDifference() {
        assertEquals((2 + 1 + 4) / 3.0, Metrics.naiveScale(new double[] {1, 3, 2, 6}), 1e-9);
    }

    @Test
    @DisplayName("naiveScale cần ít nhất hai quan sát")
    void naiveScaleNeedsTwoPoints() {
        assertTrue(Double.isNaN(Metrics.naiveScale(new double[] {5})));
        assertTrue(Double.isNaN(Metrics.naiveScale(null)));
    }

    @Test
    @DisplayName("beatsNaive chỉ đúng khi MASE < 1")
    void beatsNaiveReflectsMase() {
        assertFalse(Metrics.of(ACTUAL, PREDICTED, 2.0).beatsNaive());
        assertTrue(Metrics.of(ACTUAL, PREDICTED, 10.0).beatsNaive());
        assertFalse(Metrics.EMPTY.beatsNaive());
    }

    @Test
    @DisplayName("Dự báo hoàn hảo cho sai số bằng 0")
    void perfectForecastHasZeroError() {
        Metrics metrics = Metrics.of(ACTUAL, ACTUAL.clone(), 1.0);
        assertAll(
                () -> assertEquals(0.0, metrics.mae(), 1e-12),
                () -> assertEquals(0.0, metrics.rmse(), 1e-12),
                () -> assertEquals(0.0, metrics.mape(), 1e-12),
                () -> assertTrue(metrics.beatsNaive()));
    }

    @Test
    @DisplayName("Mảng rỗng trả về EMPTY thay vì chia cho 0")
    void emptyInputIsHandled() {
        assertEquals(0, Metrics.of(new double[0], new double[0], 1.0).sample());
    }

    @Test
    @DisplayName("Độ dài không khớp bị từ chối")
    void mismatchedLengthsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Metrics.of(new double[] {1, 2}, new double[] {1}, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> Metrics.of(null, new double[] {1}, 1.0));
    }

    @Test
    @DisplayName("Giá trị thực bằng 0 không làm hỏng MAPE")
    void zeroActualIsExcludedFromMape() {
        Metrics metrics = Metrics.of(new double[] {0, 10}, new double[] {1, 11}, 1.0);
        assertEquals(10.0, metrics.mape(), 1e-9, "Chỉ điểm khác 0 được tính vào MAPE");
    }
}
