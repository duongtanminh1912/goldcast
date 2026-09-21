package vn.goldcast.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateParsingTest {

    private static void assertNumber(String input, String expected) {
        BigDecimal parsed = DateParsing.parseVietnameseNumber(input);
        assertEquals(0, parsed.compareTo(new BigDecimal(expected)),
                "'" + input + "' phải cho " + expected + ", nhận được " + parsed);
    }

    @Test
    @DisplayName("Dấu chấm và dấu phẩy phân nhóm hàng nghìn đều được hiểu đúng")
    void groupingSeparatorsBothWork() {
        assertNumber("12.345.000", "12345000");
        assertNumber("12,345,000", "12345000");
        assertNumber("1,500", "1500");
        assertNumber(" 80 000 000 ", "80000000");
    }

    @Test
    @DisplayName("Dấu thập phân được phân biệt với dấu phân nhóm")
    void decimalSeparatorIsDistinguished() {
        assertNumber("1,5", "1.5");
        assertNumber("2400.55", "2400.55");
    }

    @Test
    @DisplayName("Định dạng hỗn hợp: dấu bên phải nhất là dấu thập phân")
    void mixedSeparatorsUseRightmostAsDecimal() {
        assertNumber("1.234,56", "1234.56");
        assertNumber("1,234.56", "1234.56");
    }

    @Test
    @DisplayName("Số âm giữ nguyên dấu")
    void negativeNumbersAreParsed() {
        assertNumber("-1.5", "-1.5");
    }

    @Test
    @DisplayName("Chuỗi không phải số trả về null thay vì ném lỗi")
    void nonNumericInputReturnsNull() {
        assertAll(
                () -> assertNull(DateParsing.parseVietnameseNumber("abc")),
                () -> assertNull(DateParsing.parseVietnameseNumber("12abc")),
                () -> assertNull(DateParsing.parseVietnameseNumber(null)),
                () -> assertNull(DateParsing.parseVietnameseNumber("   ")));
    }

    @Test
    @DisplayName("Nhận diện nhiều định dạng ngày thường gặp")
    void parsesCommonDateFormats() {
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 9, 21), DateParsing.parseFlexible("2026-09-21")),
                () -> assertEquals(LocalDate.of(2026, 9, 21), DateParsing.parseFlexible("21/09/2026")),
                () -> assertEquals(LocalDate.of(2026, 9, 5), DateParsing.parseFlexible("5/9/2026")),
                () -> assertEquals(LocalDate.of(2026, 9, 21), DateParsing.parseFlexible("21-09-2026")));
    }

    @Test
    @DisplayName("Tách được ngày nằm lẫn trong câu chữ")
    void findsDateInsideText() {
        assertAll(
                () -> assertEquals(LocalDate.of(2026, 9, 21),
                        DateParsing.parseFlexible("21/09/2026 14:30")),
                () -> assertEquals(LocalDate.of(2026, 9, 21),
                        DateParsing.parseFlexible("Cập nhật lúc 21/09/2026")));
    }

    @Test
    @DisplayName("Không tìm thấy ngày thì trả về null")
    void returnsNullWhenNoDatePresent() {
        assertAll(
                () -> assertNull(DateParsing.parseFlexible("không có ngày")),
                () -> assertNull(DateParsing.parseFlexible(null)),
                () -> assertNull(DateParsing.parseFlexible("")));
    }
}
