package vn.goldcast.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwelveDataParserTest {

    /** Shape of a real /time_series response: newest bar first, numbers as strings. */
    private static final String OK = """
            {
              "meta": {"symbol": "XAU/USD", "interval": "1day", "currency_base": "Gold Spot",
                       "currency_quote": "US Dollar", "type": "Physical Currency"},
              "values": [
                {"datetime": "2026-09-30", "open": "4170.1", "high": "4190.0", "low": "4160.2", "close": "4183.11"},
                {"datetime": "2026-09-29", "open": "4150.0", "high": "4175.5", "low": "4140.0", "close": "4170.10"},
                {"datetime": "2026-09-28", "open": "4140.0", "high": "4155.0", "low": "4130.0", "close": "not-a-number"},
                {"datetime": "garbage",    "open": "1",      "high": "1",      "low": "1",      "close": "1"},
                {"datetime": "2026-09-25", "open": "4120.0", "high": "4145.0", "low": "4110.0", "close": "4141.75"}
              ],
              "status": "ok"
            }
            """;

    @Test
    @DisplayName("Đọc chuỗi ngày, sắp xếp từ cũ đến mới và bỏ dòng hỏng")
    void parsesAndSortsChronologically() {
        List<ProviderQuote> quotes = TwelveDataParser.parse("XAUUSD", OK, null);
        assertAll(
                () -> assertEquals(3, quotes.size()),
                () -> assertEquals(LocalDate.of(2026, 9, 25), quotes.get(0).observedOn()),
                () -> assertEquals(LocalDate.of(2026, 9, 30), quotes.get(2).observedOn()),
                () -> assertEquals(0, quotes.get(2).close().compareTo(new BigDecimal("4183.11"))),
                () -> assertTrue(quotes.stream().allMatch(q -> q.instrumentCode().equals("XAUUSD"))));
    }

    @Test
    @DisplayName("Lọc bỏ dòng cũ hơn mốc earliest")
    void dropsRowsBeforeEarliest() {
        List<ProviderQuote> quotes =
                TwelveDataParser.parse("XAUUSD", OK, LocalDate.of(2026, 9, 29));
        assertEquals(2, quotes.size());
    }

    @Test
    @DisplayName("Mốc giờ trong datetime (dữ liệu intraday) chỉ giữ phần ngày")
    void keepsDatePartOfTimestamp() {
        String json = """
                {"values": [{"datetime": "2026-09-30 15:00:00", "close": "26350.5"}], "status": "ok"}
                """;
        assertEquals(LocalDate.of(2026, 9, 30),
                TwelveDataParser.parse("USDVND", json, null).get(0).observedOn());
    }

    @Test
    @DisplayName("Tài liệu lỗi (sai API key, hết quota) được báo rõ thay vì trả về rỗng")
    void errorDocumentIsReported() {
        String error = """
                {"code": 401, "message": "**apikey** parameter is incorrect or not specified.", "status": "error"}
                """;
        TwelveDataParser.TwelveDataException ex = assertThrows(
                TwelveDataParser.TwelveDataException.class,
                () -> TwelveDataParser.parse("XAUUSD", error, null));
        assertTrue(ex.getMessage().contains("401"), ex.getMessage());
    }

    @Test
    @DisplayName("Body không phải JSON (trang chặn bot, HTML lỗi) bị từ chối")
    void nonJsonIsRejected() {
        assertAll(
                () -> assertThrows(TwelveDataParser.TwelveDataException.class,
                        () -> TwelveDataParser.parse("XAUUSD", "<!DOCTYPE html><html></html>", null)),
                () -> assertThrows(TwelveDataParser.TwelveDataException.class,
                        () -> TwelveDataParser.parse("XAUUSD", "", null)));
    }

    @Test
    @DisplayName("Phản hồi hợp lệ nhưng không có dòng nào cho ra danh sách rỗng")
    void emptyValuesGivesEmptyList() {
        assertTrue(TwelveDataParser.parse("XAUUSD", "{\"values\": [], \"status\": \"ok\"}", null).isEmpty());
    }
}
