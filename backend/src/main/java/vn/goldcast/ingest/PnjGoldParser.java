package vn.goldcast.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses PNJ's public gold-price JSON for one branch zone.
 *
 * <pre>
 *   {"chinhanh": "hochiminh", "updateDate": "02/10/2026 13:35:12",
 *    "data": [{"masp": "SJC", "tensp": "Vàng miếng SJC 999.9", "giamua": 14110, "giaban": 14410}, ...]}
 * </pre>
 *
 * <p>Prices are in <em>thousand đồng per chỉ</em>. They are converted explicitly — ×1 000
 * for the unit, ×10 chỉ per lượng — rather than through {@link SjcXmlParser}'s
 * "multiply by 1 000 until plausible" heuristic: 14 410 × 1 000 already looks like a
 * plausible price per lượng and would silently land ten times too low.
 */
public final class PnjGoldParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Thousand đồng → đồng, then one chỉ → one lượng (10 chỉ). */
    private static final BigDecimal TO_VND_PER_TAEL = new BigDecimal("10000");

    private PnjGoldParser() {}

    /**
     * @param json         raw response body for one zone
     * @param fallbackDate used when the payload carries no readable update date
     * @param productCodes our instrument code → PNJ's {@code masp} to pick from this zone
     * @return one two-way quote per instrument found; unknown or implausible rows are skipped
     * @throws IllegalArgumentException if the body is not the expected JSON
     */
    public static List<ProviderQuote> parse(String json, LocalDate fallbackDate,
                                            Map<String, String> productCodes) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json == null ? "" : json);
        } catch (Exception ex) {
            throw new IllegalArgumentException("PNJ không trả về JSON hợp lệ: " + ex.getMessage());
        }
        if (root == null || !root.path("data").isArray()) {
            throw new IllegalArgumentException("PNJ trả về JSON không có mảng 'data'");
        }

        LocalDate date = DateParsing.parseFlexible(root.path("updateDate").asText(""));
        if (date == null) {
            date = fallbackDate;
        }

        List<ProviderQuote> out = new ArrayList<>();
        for (Map.Entry<String, String> wanted : productCodes.entrySet()) {
            for (JsonNode row : root.path("data")) {
                if (!wanted.getValue().equalsIgnoreCase(row.path("masp").asText())) {
                    continue;
                }
                BigDecimal buy = toVndPerTael(row.path("giamua"));
                BigDecimal sell = toVndPerTael(row.path("giaban"));
                if (buy != null && sell != null && buy.compareTo(sell) <= 0) {
                    out.add(ProviderQuote.twoWay(wanted.getKey(), date, buy, sell));
                }
                break;
            }
        }
        return out;
    }

    /** Converted price, or {@code null} when missing or outside the plausible band. */
    static BigDecimal toVndPerTael(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        BigDecimal raw;
        try {
            raw = new BigDecimal(node.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        BigDecimal value = raw.multiply(TO_VND_PER_TAEL);
        if (value.compareTo(SjcXmlParser.MIN_PLAUSIBLE_VND_PER_TAEL) < 0
                || value.compareTo(SjcXmlParser.MAX_PLAUSIBLE_VND_PER_TAEL) > 0) {
            return null;
        }
        return value;
    }
}
