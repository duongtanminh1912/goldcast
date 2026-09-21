package vn.goldcast.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldUnitsTest {

    private static final double USD_PER_OUNCE = 2400.0;
    private static final double USD_VND = 25_400.0;

    @Test
    @DisplayName("Quy đổi USD/oz sang VNĐ/lượng theo đúng công thức khối lượng")
    void convertsWorldPriceToVndPerTael() {
        double expected = USD_PER_OUNCE * (37.5 / 31.1034768) * USD_VND;
        assertEquals(expected, GoldUnits.worldToVndPerTael(USD_PER_OUNCE, USD_VND), 1e-6);
    }

    @Test
    @DisplayName("Giá trị quy đổi nằm trong khoảng hợp lý với thị trường thật")
    void convertedValueIsPlausible() {
        double vnd = GoldUnits.worldToVndPerTael(USD_PER_OUNCE, USD_VND);
        assertTrue(vnd > 70_000_000 && vnd < 80_000_000,
                "Vàng 2400 USD/oz ở tỷ giá 25.400 phải quanh 73 triệu/lượng, nhận được " + vnd);
    }

    @Test
    @DisplayName("Quy đổi ngược trả về đúng giá gốc")
    void conversionRoundTrips() {
        double vnd = GoldUnits.worldToVndPerTael(USD_PER_OUNCE, USD_VND);
        assertEquals(USD_PER_OUNCE, GoldUnits.vndPerTaelToWorld(vnd, USD_VND), 1e-6);
    }

    @Test
    @DisplayName("Một lượng bằng 10 chỉ và 37,5 gram")
    void taelSubdivisions() {
        double perTael = 80_000_000;
        assertAll(
                () -> assertEquals(8_000_000, GoldUnits.taelToChi(perTael), 1e-9),
                () -> assertEquals(perTael / 37.5, GoldUnits.taelToGram(perTael), 1e-9),
                () -> assertEquals(3.75, GoldUnits.CHI_IN_GRAMS, 1e-9));
    }

    @Test
    @DisplayName("Chênh lệch trong nước tính đúng cả giá trị tuyệt đối lẫn phần trăm")
    void premiumIsComputedBothWays() {
        double world = 73_000_000;
        GoldUnits.Premium premium = GoldUnits.premium(world * 1.2, world);

        assertAll(
                () -> assertEquals(20.0, premium.percent(), 1e-6),
                () -> assertEquals(world * 0.2, premium.amountVnd(), 1e-6));
    }

    @Test
    @DisplayName("Chênh lệch âm khi giá trong nước thấp hơn giá thế giới quy đổi")
    void premiumCanBeNegative() {
        GoldUnits.Premium premium = GoldUnits.premium(70_000_000, 73_000_000);
        assertAll(
                () -> assertTrue(premium.amountVnd() < 0),
                () -> assertTrue(premium.percent() < 0));
    }

    @Test
    @DisplayName("Đầu vào không hợp lệ bị từ chối thay vì tạo ra số vô nghĩa")
    void rejectsInvalidInput() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GoldUnits.worldToVndPerTael(-1, USD_VND)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GoldUnits.worldToVndPerTael(USD_PER_OUNCE, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GoldUnits.worldToVndPerTael(Double.NaN, USD_VND)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> GoldUnits.premium(0, 73_000_000)));
    }

    @Test
    @DisplayName("Hằng số khối lượng khớp với định nghĩa chuẩn")
    void massConstantsAreCorrect() {
        assertAll(
                () -> assertEquals(31.1034768, GoldUnits.TROY_OUNCE_IN_GRAMS, 1e-9),
                () -> assertEquals(37.5, GoldUnits.TAEL_IN_GRAMS, 1e-9),
                () -> assertEquals(37.5 / 31.1034768, GoldUnits.TAEL_IN_TROY_OUNCES, 1e-12));
    }
}
