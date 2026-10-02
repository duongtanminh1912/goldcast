package vn.goldcast.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PnjGoldParserTest {

    /** Trimmed from a real zone=00 response, numbers in thousand đồng per chỉ. */
    private static final String ZONE_HCM = """
            {"data": [
               {"masp": "SJC",  "tensp": "Vàng miếng SJC 999.9", "giaban": 14410, "giamua": 14110},
               {"masp": "N24K", "tensp": "Nhẫn Trơn PNJ 999.9",  "giaban": 14410, "giamua": 14110},
               {"masp": "24K",  "tensp": "Vàng nữ trang 999.9",  "giaban": 14240, "giamua": 13740}
             ],
             "chinhanh": "hochiminh",
             "updateDate": "02/10/2026 13:35:12"}
            """;

    @Test
    @DisplayName("Quy đổi nghìn đồng/chỉ sang đồng/lượng (×10 000) và lấy ngày từ updateDate")
    void convertsUnitsAndReadsDate() {
        List<ProviderQuote> quotes = PnjGoldParser.parse(ZONE_HCM, LocalDate.of(2000, 1, 1),
                Map.of("SJC_HCM", "SJC"));

        ProviderQuote sjc = quotes.get(0);
        assertAll(
                () -> assertEquals(1, quotes.size()),
                () -> assertEquals("SJC_HCM", sjc.instrumentCode()),
                () -> assertEquals(LocalDate.of(2026, 10, 2), sjc.observedOn()),
                () -> assertEquals(0, sjc.buy().compareTo(new BigDecimal("141100000"))),
                () -> assertEquals(0, sjc.sell().compareTo(new BigDecimal("144100000"))),
                // Close is the sell side, as for every other two-way quote.
                () -> assertEquals(0, sjc.close().compareTo(sjc.sell())));
    }

    @Test
    @DisplayName("Đọc nhiều sản phẩm trong cùng một khu vực bằng một lần parse")
    void readsSeveralProductsFromOneZone() {
        List<ProviderQuote> quotes = PnjGoldParser.parse(ZONE_HCM, LocalDate.now(),
                Map.of("SJC_HCM", "SJC", "PNJ_RING", "N24K"));
        assertEquals(2, quotes.size());
    }

    @Test
    @DisplayName("Mã sản phẩm không có trong bảng giá thì bị bỏ qua")
    void unknownProductIsSkipped() {
        assertTrue(PnjGoldParser.parse(ZONE_HCM, LocalDate.now(), Map.of("X", "KHONG_CO")).isEmpty());
    }

    @Test
    @DisplayName("Thiếu updateDate thì dùng ngày dự phòng")
    void fallsBackToGivenDate() {
        String json = """
                {"data": [{"masp": "SJC", "giaban": 14410, "giamua": 14110}]}
                """;
        LocalDate fallback = LocalDate.of(2026, 9, 30);
        assertEquals(fallback,
                PnjGoldParser.parse(json, fallback, Map.of("SJC_HCM", "SJC")).get(0).observedOn());
    }

    @Test
    @DisplayName("Giá ngoài khoảng hợp lý hoặc mua cao hơn bán thì bị loại, không ghi sai")
    void implausibleRowsAreDropped() {
        String json = """
                {"data": [
                   {"masp": "TINY", "giaban": 1,     "giamua": 1},
                   {"masp": "FLIP", "giaban": 14000, "giamua": 14500}
                 ]}
                """;
        assertAll(
                () -> assertTrue(PnjGoldParser.parse(json, LocalDate.now(), Map.of("A", "TINY")).isEmpty()),
                () -> assertTrue(PnjGoldParser.parse(json, LocalDate.now(), Map.of("B", "FLIP")).isEmpty()),
                () -> assertNull(PnjGoldParser.toVndPerTael(null)));
    }

    @Test
    @DisplayName("Body không phải JSON hoặc thiếu mảng data bị từ chối")
    void malformedBodyIsRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PnjGoldParser.parse("<html>Just a moment...</html>", LocalDate.now(), Map.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PnjGoldParser.parse("{\"status\": \"ok\"}", LocalDate.now(), Map.of())));
    }
}
