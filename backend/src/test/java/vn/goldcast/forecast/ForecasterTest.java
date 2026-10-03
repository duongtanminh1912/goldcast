package vn.goldcast.forecast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecasterTest {

    private static double[] linear(int n, double start, double step) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    @Test
    @DisplayName("Naive lặp lại giá trị cuối cho mọi bước")
    void naiveRepeatsLastValue() {
        double[] result = new NaiveForecaster().forecast(new double[] {100, 101, 99, 102}, 3).values();
        assertArrayEqualsWithin(new double[] {102, 102, 102}, result, 1e-9);
    }

    @Test
    @DisplayName("Drift ngoại suy đúng độ dốc trên chuỗi tuyến tính")
    void driftExtrapolatesSlope() {
        double[] result = new DriftForecaster().forecast(linear(20, 100, 1), 3).values();
        assertArrayEqualsWithin(new double[] {120, 121, 122}, result, 1e-9);
    }

    @Test
    @DisplayName("Drift không cho giá âm khi chuỗi giảm mạnh")
    void driftClampsAtZero() {
        double[] falling = {100, 80, 60, 40, 20, 5};
        double[] result = new DriftForecaster().forecast(falling, 10).values();
        assertTrue(Arrays.stream(result).allMatch(value -> value > 0),
                "Giá dự báo phải luôn dương: " + Arrays.toString(result));
    }

    @Test
    @DisplayName("SMA dự báo phẳng bằng trung bình cửa sổ cuối")
    void smaIsFlatAtWindowMean() {
        double[] result = new MovingAverageForecaster(3).forecast(new double[] {1, 2, 3, 4, 5, 6}, 2).values();
        assertAll(
                () -> assertEquals(5.0, result[0], 1e-9),
                () -> assertEquals(result[0], result[1], 1e-9));
    }

    @Test
    @DisplayName("SMA từ chối cửa sổ nhỏ hơn 2")
    void smaRejectsTinyWindow() {
        assertThrows(IllegalArgumentException.class, () -> new MovingAverageForecaster(1));
    }

    @Test
    @DisplayName("Holt có xu hướng tắt dần: không ngoại suy đà tăng vô hạn")
    void holtDampensTheTrend() {
        double[] series = linear(40, 100, 1);
        double[] result = new HoltDampedForecaster().forecast(series, 10).values();

        double last = series[series.length - 1];
        assertTrue(result[0] > last, "Bước đầu phải tiếp tục xu hướng tăng");
        // Undamped linear extrapolation would reach last + 10; damping must stay at or below it.
        assertTrue(result[9] <= last + 10 + 1e-6,
                "Xu hướng phải bị tắt dần, nhận được " + result[9]);
    }

    @Test
    @DisplayName("Holt ước lượng được bộ tham số alpha/beta/phi")
    void holtReportsFittedParameters() {
        PointForecast forecast = new HoltDampedForecaster().forecast(linear(40, 100, 1), 5);
        assertAll(
                () -> assertTrue(forecast.params().containsKey("alpha")),
                () -> assertTrue(forecast.params().containsKey("beta")),
                () -> assertTrue(forecast.params().containsKey("phi")),
                () -> assertTrue(forecast.params().get("phi") <= 1.0));
    }

    @Test
    @DisplayName("AR(p) khôi phục đúng hệ số của quá trình AR(1) đã biết")
    void arRecoversKnownCoefficient() {
        double[] series = arOneProcess(400, 0.4, -0.6, 42L);
        PointForecast forecast = new ArDiffForecaster(5).forecast(series, 5);

        assertEquals(-0.6, forecast.params().get("phi1"), 0.05,
                "Hệ số AR bậc 1 ước lượng được phải gần giá trị sinh dữ liệu");
    }

    @Test
    @DisplayName("AR(p) suy biến về Drift thay vì trả về giá trị vô nghĩa")
    void arDegradesGracefullyOnConstantSeries() {
        double[] constant = new double[60];
        Arrays.fill(constant, 50.0);

        double[] result = new ArDiffForecaster(5).forecast(constant, 3).values();
        assertTrue(Arrays.stream(result).allMatch(value -> Math.abs(value - 50) < 1e-6),
                "Chuỗi hằng số phải cho dự báo hằng số: " + Arrays.toString(result));
    }

    @Test
    @DisplayName("Mọi mô hình từ chối chuỗi ngắn hơn yêu cầu tối thiểu")
    void modelsRejectTooShortSeries() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new HoltDampedForecaster().forecast(new double[] {1, 2}, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ArDiffForecaster(5).forecast(new double[] {1, 2, 3}, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new NaiveForecaster().forecast(new double[] {1}, 0)));
    }

    @Test
    @DisplayName("PointForecast sao chép phòng vệ mảng giá trị")
    void pointForecastIsImmutable() {
        PointForecast forecast = PointForecast.of(ForecastModel.NAIVE, new double[] {1, 2, 3});
        forecast.values()[0] = 999;
        assertEquals(1.0, forecast.values()[0], 1e-9);
    }

    @Test
    @DisplayName("Forecasters.candidates lọc theo độ dài dữ liệu và không bao giờ rỗng")
    void candidateSelectionRespectsDataLength() {
        assertAll(
                () -> assertEquals(5, Forecasters.candidates(500, 7).size()),
                () -> assertTrue(Forecasters.candidates(8, 3).size() <= 3),
                () -> assertTrue(!Forecasters.candidates(1, 30).isEmpty()));
    }

    @Test
    @DisplayName("AUTO không phải mô hình khớp được")
    void autoIsNotDirectlyInstantiable() {
        assertThrows(IllegalArgumentException.class, () -> Forecasters.create(ForecastModel.AUTO));
    }

    @Test
    @DisplayName("Mô hình khác nhau cho kết quả khác nhau trên chuỗi có xu hướng")
    void modelsDisagreeOnTrendingSeries() {
        double[] series = linear(60, 100, 0.5);
        double naive = new NaiveForecaster().forecast(series, 5).values()[4];
        double drift = new DriftForecaster().forecast(series, 5).values()[4];
        assertNotEquals(naive, drift, 1e-6);
    }

    /** AR(1) on first differences with reproducible Gaussian noise. */
    private static double[] arOneProcess(int n, double intercept, double phi, long seed) {
        double[] out = new double[n];
        out[0] = 100;
        double diff = intercept / (1 - phi);
        java.util.Random random = new java.util.Random(seed);
        for (int i = 1; i < n; i++) {
            diff = intercept + phi * diff + random.nextGaussian();
            out[i] = out[i - 1] + diff;
        }
        return out;
    }

    private static void assertArrayEqualsWithin(double[] expected, double[] actual, double tolerance) {
        assertEquals(expected.length, actual.length, "Độ dài dự báo không khớp");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], tolerance, "Sai lệch tại bước " + (i + 1));
        }
    }
}
