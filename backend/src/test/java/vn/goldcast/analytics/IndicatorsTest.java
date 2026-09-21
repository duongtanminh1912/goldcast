package vn.goldcast.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorsTest {

    private static double[] rising(int n, double start, double step) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    @Test
    @DisplayName("SMA trả về mảng cùng độ dài, NaN ở phần đầu chưa xác định")
    void smaAlignsWithInput() {
        double[] sma = Indicators.sma(new double[] {1, 2, 3, 4, 5, 6}, 3);
        assertAll(
                () -> assertEquals(6, sma.length),
                () -> assertTrue(Double.isNaN(sma[0])),
                () -> assertTrue(Double.isNaN(sma[1])),
                () -> assertEquals(2.0, sma[2], 1e-9),
                () -> assertEquals(5.0, sma[5], 1e-9));
    }

    @Test
    @DisplayName("SMA trả về toàn NaN khi dữ liệu ngắn hơn cửa sổ")
    void smaHandlesShortSeries() {
        double[] sma = Indicators.sma(new double[] {1, 2}, 5);
        assertTrue(Arrays.stream(sma).allMatch(Double::isNaN));
    }

    @Test
    @DisplayName("EMA được mồi bằng SMA của cửa sổ đầu tiên")
    void emaIsSeededWithSma() {
        double[] ema = Indicators.ema(new double[] {1, 2, 3, 4, 5, 6}, 3);
        assertAll(
                () -> assertTrue(Double.isNaN(ema[1])),
                () -> assertEquals(2.0, ema[2], 1e-9),
                () -> assertEquals(3.0, ema[3], 1e-9));
    }

    @Test
    @DisplayName("RSI đạt 100 khi chuỗi chỉ tăng và 0 khi chỉ giảm")
    void rsiSaturatesAtExtremes() {
        assertAll(
                () -> assertEquals(100.0, Indicators.rsi(rising(30, 100, 1), 14)[29], 1e-9),
                () -> assertEquals(0.0, Indicators.rsi(rising(30, 200, -1), 14)[29], 1e-9));
    }

    @Test
    @DisplayName("RSI chưa xác định trước khi đủ chu kỳ")
    void rsiUndefinedBeforePeriod() {
        double[] rsi = Indicators.rsi(rising(30, 100, 1), 14);
        assertAll(
                () -> assertTrue(Double.isNaN(rsi[13])),
                () -> assertTrue(!Double.isNaN(rsi[14])));
    }

    @Test
    @DisplayName("RSI luôn nằm trong đoạn [0, 100]")
    void rsiStaysInRange() {
        double[] noisy = {100, 103, 99, 105, 101, 98, 107, 104, 102, 110,
                          108, 99, 95, 101, 106, 103, 111, 109, 104, 100};
        for (double value : Indicators.rsi(noisy, 14)) {
            if (!Double.isNaN(value)) {
                assertTrue(value >= 0 && value <= 100, "RSI ngoài khoảng: " + value);
            }
        }
    }

    @Test
    @DisplayName("Bollinger co về đường giữa khi giá không đổi")
    void bollingerCollapsesOnFlatSeries() {
        double[] flat = new double[25];
        Arrays.fill(flat, 7.0);
        Indicators.Bands bands = Indicators.bollinger(flat, 20, 2);

        assertAll(
                () -> assertEquals(7.0, bands.middle()[24], 1e-9),
                () -> assertEquals(7.0, bands.upper()[24], 1e-9),
                () -> assertEquals(7.0, bands.lower()[24], 1e-9));
    }

    @Test
    @DisplayName("Bollinger giữ đúng thứ tự dưới < giữa < trên khi có biến động")
    void bollingerBandsAreOrdered() {
        Indicators.Bands bands = Indicators.bollinger(rising(40, 100, 1), 20, 2);
        assertTrue(bands.lower()[39] < bands.middle()[39]);
        assertTrue(bands.middle()[39] < bands.upper()[39]);
    }

    @Test
    @DisplayName("changePercent tính đúng phần trăm thay đổi")
    void changePercentIsCorrect() {
        assertEquals(10.0, Indicators.changePercent(new double[] {100, 110}, 1), 1e-9);
        assertTrue(Double.isNaN(Indicators.changePercent(new double[] {100}, 1)));
        assertTrue(Double.isNaN(Indicators.changePercent(new double[] {100, 110}, 5)));
    }

    @Test
    @DisplayName("Biến động của chuỗi tăng đều theo log bằng 0")
    void volatilityIsZeroForConstantGrowth() {
        double[] geometric = new double[60];
        geometric[0] = 100;
        for (int i = 1; i < geometric.length; i++) {
            geometric[i] = geometric[i - 1] * 1.001;
        }
        assertEquals(0.0, Indicators.annualisedVolatility(geometric, 252), 1e-6);
    }

    @Test
    @DisplayName("Tham số không hợp lệ bị từ chối rõ ràng")
    void invalidParametersRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Indicators.sma(null, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Indicators.sma(new double[] {1, 2, 3}, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Indicators.rsi(new double[] {1, 2, 3}, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> Indicators.bollinger(new double[] {1, 2, 3}, 20, 0)));
    }
}
