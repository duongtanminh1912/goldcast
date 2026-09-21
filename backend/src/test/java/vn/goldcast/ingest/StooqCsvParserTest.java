package vn.goldcast.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StooqCsvParserTest {

    private static final String CSV = String.join("\n",
            "Date,Open,High,Low,Close",
            "2026-09-15,2400.10,2410.00,2395.00,2405.50",
            "2026-09-16,2405.50,2420.00,2400.00,2418.25",
            "2026-09-17,N/D,N/D,N/D,N/D",
            "2026-09-18,2418.25,2430.00,2415.00,2429.00",
            "bad-line",
            "not-a-date,1,2,3,4",
            "");

    @Test
    @DisplayName("Bỏ dòng tiêu đề và chỉ giữ dòng hợp lệ")
    void parsesOnlyValidRows() {
        List<ProviderQuote> quotes = StooqCsvParser.parse("XAUUSD", CSV, null);
        assertAll(
                () -> assertEquals(3, quotes.size()),
                () -> assertEquals(LocalDate.of(2026, 9, 15), quotes.get(0).observedOn()),
                () -> assertEquals(0, quotes.get(0).close().compareTo(new BigDecimal("2405.50"))));
    }

    @Test
    @DisplayName("Dòng N/D bị bỏ qua chứ không nội suy")
    void skipsUnavailableRows() {
        List<ProviderQuote> quotes = StooqCsvParser.parse("XAUUSD", CSV, null);
        assertTrue(quotes.stream().noneMatch(q -> q.observedOn().equals(LocalDate.of(2026, 9, 17))),
                "Ngày không có dữ liệu phải giữ nguyên là khoảng trống");
    }

    @Test
    @DisplayName("Chuỗi giá một chiều không có buy/sell")
    void worldQuotesHaveNoSpread() {
        ProviderQuote quote = StooqCsvParser.parse("XAUUSD", CSV, null).get(0);
        assertAll(
                () -> assertNull(quote.buy()),
                () -> assertNull(quote.sell()));
    }

    @Test
    @DisplayName("Tham số earliest lọc bỏ dữ liệu quá cũ")
    void earliestFilterApplies() {
        assertEquals(1, StooqCsvParser.parse("XAUUSD", CSV, LocalDate.of(2026, 9, 17)).size());
    }

    @Test
    @DisplayName("Đầu vào rỗng, null hoặc chỉ có tiêu đề đều trả về danh sách rỗng")
    void emptyInputsReturnEmptyList() {
        assertAll(
                () -> assertTrue(StooqCsvParser.parse("XAUUSD", null, null).isEmpty()),
                () -> assertTrue(StooqCsvParser.parse("XAUUSD", "", null).isEmpty()),
                () -> assertTrue(StooqCsvParser.parse("XAUUSD", "   ", null).isEmpty()),
                () -> assertTrue(StooqCsvParser.parse("XAUUSD", "Date,Open,High,Low,Close", null).isEmpty()));
    }

    @Test
    @DisplayName("Xử lý được xuống dòng kiểu Windows")
    void handlesCrlfLineEndings() {
        assertEquals(1, StooqCsvParser.parse("XAUUSD",
                "Date,O,H,L,C\r\n2026-01-02,1,2,3,4.5\r\n", null).size());
    }

    @Test
    @DisplayName("Giá bằng 0 hoặc âm bị loại")
    void rejectsNonPositivePrices() {
        String csv = "Date,O,H,L,C\n2026-01-02,1,2,3,0\n2026-01-03,1,2,3,-5\n2026-01-04,1,2,3,7";
        List<ProviderQuote> quotes = StooqCsvParser.parse("XAUUSD", csv, null);
        assertEquals(1, quotes.size());
        assertEquals(LocalDate.of(2026, 1, 4), quotes.get(0).observedOn());
    }
}
