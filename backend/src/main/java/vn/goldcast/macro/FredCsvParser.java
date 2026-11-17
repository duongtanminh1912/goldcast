package vn.goldcast.macro;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parses FRED's graph CSV, e.g.
 *
 * <pre>
 * observation_date,DFII10
 * 2026-11-10,2.78
 * 2026-11-11,
 * 2026-11-12,2.86
 * </pre>
 *
 * <p>Missing days arrive as an empty cell (older exports used {@code .}) and are skipped,
 * never read as zero. The header must name the series that was requested: an error page,
 * an HTML body or a different series yields an empty list rather than wrong numbers.
 */
public final class FredCsvParser {

    private FredCsvParser() {}

    /**
     * @param fredId   series that was requested, e.g. {@code DFII10}
     * @param csv      raw response body, may be {@code null}
     * @param earliest observations before this date are dropped
     * @return observations oldest first, one per date
     */
    public static List<MacroObservation> parse(String fredId, String csv, LocalDate earliest) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        String[] lines = csv.replace("\r", "").split("\n");
        if (!isHeader(lines[0], fredId)) {
            return List.of();
        }

        Map<LocalDate, Double> byDate = new TreeMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            int comma = line.indexOf(',');
            if (line.isEmpty() || comma < 0 || line.indexOf(',', comma + 1) >= 0) {
                continue;
            }
            String rawValue = line.substring(comma + 1).trim();
            if (rawValue.isEmpty() || rawValue.equals(".")) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(line.substring(0, comma).trim());
                double value = Double.parseDouble(rawValue);
                if (Double.isFinite(value) && (earliest == null || !date.isBefore(earliest))) {
                    byDate.put(date, value);
                }
            } catch (DateTimeParseException | NumberFormatException ignored) {
                // A malformed row is dropped; one bad line must not discard the series.
            }
        }

        List<MacroObservation> out = new ArrayList<>(byDate.size());
        byDate.forEach((date, value) -> out.add(new MacroObservation(date, value)));
        return out;
    }

    private static boolean isHeader(String line, String fredId) {
        String[] cells = line.replace("﻿", "").trim().split(",");
        return cells.length == 2
                && (cells[0].trim().equalsIgnoreCase("observation_date") || cells[0].trim().equalsIgnoreCase("DATE"))
                && cells[1].trim().equalsIgnoreCase(fredId);
    }
}
