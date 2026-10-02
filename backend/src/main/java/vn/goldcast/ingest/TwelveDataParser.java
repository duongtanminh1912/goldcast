package vn.goldcast.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON returned by Twelve Data's {@code /time_series} endpoint.
 *
 * <pre>
 *   {"meta": {...}, "values": [{"datetime": "2026-09-30", "close": "2650.12", ...}], "status": "ok"}
 *   {"code": 401, "message": "...", "status": "error"}
 * </pre>
 *
 * <p>Kept free of Spring and HTTP, like {@link StooqCsvParser}, so the rules can be tested
 * against captured payloads. An error document is surfaced as {@link TwelveDataException}
 * rather than an empty list: "your API key is wrong" and "the market was closed" must not
 * look the same in the logs.
 */
public final class TwelveDataParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TwelveDataParser() {}

    /**
     * @param instrumentCode the series these rows belong to
     * @param json           raw response body
     * @param earliest       drop rows before this date, or null to keep everything
     * @return observations oldest first; rows without a usable date or positive close are skipped
     * @throws TwelveDataException if the body is not JSON or is an error document
     */
    public static List<ProviderQuote> parse(String instrumentCode, String json, LocalDate earliest) {
        if (json == null || json.isBlank()) {
            throw new TwelveDataException("Twelve Data trả về body rỗng");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new TwelveDataException("Twelve Data không trả về JSON hợp lệ: " + ex.getMessage());
        }

        if ("error".equalsIgnoreCase(root.path("status").asText())) {
            throw new TwelveDataException("Twelve Data báo lỗi " + root.path("code").asText("?")
                    + ": " + root.path("message").asText("không rõ"));
        }

        List<ProviderQuote> out = new ArrayList<>();
        for (JsonNode row : root.path("values")) {
            LocalDate date;
            BigDecimal close;
            try {
                // Daily bars come as "yyyy-MM-dd"; intraday ones carry a time we do not want.
                date = LocalDate.parse(row.path("datetime").asText("").substring(0, 10));
                close = new BigDecimal(row.path("close").asText("").trim());
            } catch (DateTimeParseException | NumberFormatException | StringIndexOutOfBoundsException ex) {
                continue;
            }
            if (close.signum() <= 0 || (earliest != null && date.isBefore(earliest))) {
                continue;
            }
            out.add(ProviderQuote.single(instrumentCode, date, close));
        }

        // The API lists newest first; the rest of the pipeline expects chronological order.
        out.sort((a, b) -> a.observedOn().compareTo(b.observedOn()));
        return out;
    }

    /** An upstream failure worth distinguishing from "no rows in range". */
    public static final class TwelveDataException extends RuntimeException {
        public TwelveDataException(String message) {
            super(message);
        }
    }
}
