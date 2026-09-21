package vn.goldcast.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Stooq's daily CSV: {@code Date,Open,High,Low,Close}, oldest row first.
 *
 * <p>Kept free of Spring and of HTTP so the parsing rules — which is where feed bugs
 * actually live — can be tested directly against captured payloads.
 *
 * <p>Rows Stooq cannot supply are marked {@code N/D}. Those are skipped rather than
 * interpolated: a gap in the source should stay a gap, not become an invented price.
 */
public final class StooqCsvParser {

    private StooqCsvParser() {}

    /**
     * @param instrumentCode the series these rows belong to
     * @param csv            raw response body, may be null or empty
     * @param earliest       drop rows before this date, or null to keep everything
     */
    public static List<ProviderQuote> parse(String instrumentCode, String csv, LocalDate earliest) {
        List<ProviderQuote> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }

        String[] lines = csv.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (i == 0 && line.regionMatches(true, 0, "date", 0, 4)) {
                continue;
            }

            String[] cells = line.split(",");
            if (cells.length < 5) {
                continue;
            }

            LocalDate date;
            try {
                date = LocalDate.parse(cells[0].trim());
            } catch (DateTimeParseException ex) {
                continue;
            }
            if (earliest != null && date.isBefore(earliest)) {
                continue;
            }

            BigDecimal close = parseDecimal(cells[4]);
            if (close == null || close.signum() <= 0) {
                continue;
            }
            out.add(ProviderQuote.single(instrumentCode, date, close));
        }
        return out;
    }

    private static BigDecimal parseDecimal(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("N/D") || value.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
