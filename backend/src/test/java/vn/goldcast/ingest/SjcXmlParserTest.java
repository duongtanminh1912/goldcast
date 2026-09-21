package vn.goldcast.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SjcXmlParserTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <giavang>
              <ratelist date="21/09/2026" unit="nghìn đồng/lượng">
                <city name="Hồ Chí Minh">
                  <item type="Vàng SJC 1L, 10L, 1KG" buy="78.500" sell="80.500"/>
                  <item type="Vàng nhẫn SJC 99,99 1 chỉ, 2 chỉ, 5 chỉ" buy="73.200" sell="74.700"/>
                </city>
                <city name="Hà Nội">
                  <item type="Vàng SJC 1L, 10L, 1KG" buy="78.500" sell="80.520"/>
                </city>
              </ratelist>
            </giavang>
            """;

    private static ProviderQuote find(List<ProviderQuote> quotes, String code) {
        return quotes.stream()
                .filter(quote -> quote.instrumentCode().equals(code))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Tách đúng ba chuỗi giá từ feed nhiều thành phố")
    void extractsEveryRequestedSeries() {
        List<ProviderQuote> quotes =
                SjcXmlParser.parse(FEED, LocalDate.of(2000, 1, 1), List.of("SJC_HCM", "SJC_HN", "SJC_RING"));
        assertEquals(3, quotes.size());
    }

    @Test
    @DisplayName("Giá nghìn đồng được quy về đồng mỗi lượng")
    void scalesThousandsToDong() {
        ProviderQuote hcm = find(
                SjcXmlParser.parse(FEED, LocalDate.now(), List.of("SJC_HCM")), "SJC_HCM");
        assertNotNull(hcm);
        assertAll(
                () -> assertEquals(0, hcm.buy().compareTo(new BigDecimal("78500000"))),
                () -> assertEquals(0, hcm.sell().compareTo(new BigDecimal("80500000"))),
                () -> assertEquals(0, hcm.close().compareTo(hcm.sell()),
                        "close phải bằng giá bán ra — giá người mua thực trả"));
    }

    @Test
    @DisplayName("Phân biệt đúng Hà Nội và TP.HCM dù có dấu tiếng Việt")
    void distinguishesCitiesDespiteDiacritics() {
        List<ProviderQuote> quotes =
                SjcXmlParser.parse(FEED, LocalDate.now(), List.of("SJC_HCM", "SJC_HN"));
        assertEquals(0, find(quotes, "SJC_HN").sell().compareTo(new BigDecimal("80520000")));
        assertEquals(0, find(quotes, "SJC_HCM").sell().compareTo(new BigDecimal("80500000")));
    }

    @Test
    @DisplayName("Vàng nhẫn khớp đúng dòng vàng nhẫn, không lấy nhầm vàng miếng")
    void ringMatchesRingRow() {
        ProviderQuote ring = find(
                SjcXmlParser.parse(FEED, LocalDate.now(), List.of("SJC_RING")), "SJC_RING");
        assertNotNull(ring);
        assertEquals(0, ring.sell().compareTo(new BigDecimal("74700000")));
    }

    @Test
    @DisplayName("Không có dòng vàng nhẫn thì bỏ trống, không thay thế bằng vàng miếng")
    void noRingSubstitution() {
        String noRing = """
                <giavang date="2026-09-21">
                  <city name="Ho Chi Minh"><item type="SJC 1L" buy="78500" sell="80500"/></city>
                </giavang>
                """;
        assertTrue(SjcXmlParser.parse(noRing, LocalDate.now(), List.of("SJC_RING")).isEmpty());
    }

    @Test
    @DisplayName("Dùng ngày trong feed, quay về ngày dự phòng khi feed không có")
    void usesFeedDateWhenPresent() {
        ProviderQuote withDate = find(
                SjcXmlParser.parse(FEED, LocalDate.of(2000, 1, 1), List.of("SJC_HCM")), "SJC_HCM");
        assertEquals(LocalDate.of(2026, 9, 21), withDate.observedOn());

        String noDate = """
                <giavang><city name="Ho Chi Minh">
                  <item type="SJC 1L" buy="78500" sell="80500"/></city></giavang>
                """;
        ProviderQuote fallback = find(
                SjcXmlParser.parse(noDate, LocalDate.of(2026, 3, 4), List.of("SJC_HCM")), "SJC_HCM");
        assertEquals(LocalDate.of(2026, 3, 4), fallback.observedOn());
    }

    @Test
    @DisplayName("Chịu được cấu trúc XML lồng sâu và tên thẻ khác")
    void toleratesRestructuredFeed() {
        String restructured = """
                <root updated="2026-09-21">
                  <region><city name="TPHCM"><group>
                    <rate type="SJC 1L" buy="78500" sell="80500"/>
                  </group></city></region>
                </root>
                """;
        assertEquals(1, SjcXmlParser.parse(restructured, LocalDate.now(), List.of("SJC_HCM")).size());
    }

    @Test
    @DisplayName("XML hỏng hoặc trang HTML lỗi không làm sập ingest")
    void malformedInputIsSafe() {
        assertAll(
                () -> assertTrue(SjcXmlParser.parse(null, LocalDate.now(), List.of("SJC_HCM")).isEmpty()),
                () -> assertTrue(SjcXmlParser.parse("", LocalDate.now(), List.of("SJC_HCM")).isEmpty()),
                () -> assertTrue(SjcXmlParser.parse("<giavang><oops>", LocalDate.now(),
                        List.of("SJC_HCM")).isEmpty()),
                () -> assertTrue(SjcXmlParser.parse("<html><body>503 Service Unavailable</body></html>",
                        LocalDate.now(), List.of("SJC_HCM")).isEmpty()));
    }

    @Test
    @DisplayName("Giá trị vô lý bị loại thay vì lưu sai đơn vị")
    void implausibleValuesAreDropped() {
        String absurd = """
                <giavang date="2026-09-21">
                  <city name="Ho Chi Minh"><item type="SJC 1L" buy="1" sell="2"/></city>
                </giavang>
                """;
        assertTrue(SjcXmlParser.parse(absurd, LocalDate.now(), List.of("SJC_HCM")).isEmpty());
    }

    @Test
    @DisplayName("normaliseToVndPerTael đưa mọi đơn vị hợp lệ về đồng mỗi lượng")
    void normalisationHandlesEveryUnitConvention() {
        assertAll(
                () -> assertEquals(0, SjcXmlParser.normaliseToVndPerTael(
                        new BigDecimal("80000000")).compareTo(new BigDecimal("80000000"))),
                () -> assertEquals(0, SjcXmlParser.normaliseToVndPerTael(
                        new BigDecimal("80000")).compareTo(new BigDecimal("80000000"))),
                () -> assertEquals(0, SjcXmlParser.normaliseToVndPerTael(
                        new BigDecimal("80")).compareTo(new BigDecimal("80000000"))));
    }

    @Test
    @DisplayName("Giá trị mỗi chỉ bị từ chối chứ không nhận nhầm là giá mỗi lượng")
    void rejectsPerChiFigures() {
        assertNull(SjcXmlParser.normaliseToVndPerTael(new BigDecimal("8000000")));
    }

    @Test
    @DisplayName("normaliseToVndPerTael từ chối đầu vào không hợp lệ")
    void normalisationRejectsInvalidInput() {
        assertAll(
                () -> assertNull(SjcXmlParser.normaliseToVndPerTael(null)),
                () -> assertNull(SjcXmlParser.normaliseToVndPerTael(BigDecimal.ZERO)),
                () -> assertNull(SjcXmlParser.normaliseToVndPerTael(new BigDecimal("-5"))),
                () -> assertNull(SjcXmlParser.normaliseToVndPerTael(new BigDecimal("50000000000"))));
    }

    @Test
    @DisplayName("fold bỏ dấu tiếng Việt để so khớp không phụ thuộc cách viết")
    void foldStripsDiacritics() {
        assertAll(
                () -> assertEquals("ho chi minh", SjcXmlParser.fold("Hồ Chí Minh")),
                () -> assertEquals("vang nhan", SjcXmlParser.fold("Vàng nhẫn")),
                () -> assertEquals("", SjcXmlParser.fold(null)));
    }
}
