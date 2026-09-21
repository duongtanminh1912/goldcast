package vn.goldcast.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient parsing for values coming off third-party feeds.
 *
 * <p>Vietnamese sites format numbers as {@code 12.345.000} and dates as {@code dd/MM/yyyy},
 * while others use ISO and comma grouping. Both conventions are accepted rather than
 * assuming one, because a mis-parse here becomes a wrong price with no visible error.
 */
final class DateParsing {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"));

    private static final Pattern LEADING_DATE = Pattern.compile("(\\d{1,4}[-/]\\d{1,2}[-/]\\d{1,4})");

    private DateParsing() {}

    /** Parses the first date-looking token in {@code value}, or {@code null}. */
    static LocalDate parseFlexible(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();

        Matcher matcher = LEADING_DATE.matcher(trimmed);
        String candidate = matcher.find() ? matcher.group(1) : trimmed;

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(candidate, format);
            } catch (RuntimeException ignored) {
                // try the next pattern
            }
        }
        return null;
    }

    /**
     * Parses a number that may use either {@code .} or {@code ,} for grouping.
     *
     * <p>When both separators appear, the rightmost is taken as the decimal point. When
     * only one appears, it is treated as grouping if it splits the string into clean
     * three-digit groups, and as a decimal point otherwise — so {@code 1,5} is one and a
     * half while {@code 1,500} is fifteen hundred.
     */
    static BigDecimal parseVietnameseNumber(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\s\\u00A0\\u202F]", "").replace("+", "");
        if (cleaned.isEmpty()) {
            return null;
        }

        boolean negative = cleaned.startsWith("-");
        if (negative) {
            cleaned = cleaned.substring(1);
        }
        if (!cleaned.matches("[0-9.,]+")) {
            return null;
        }

        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');

        String normalised;
        if (lastComma >= 0 && lastDot >= 0) {
            char decimalSeparator = lastComma > lastDot ? ',' : '.';
            char groupSeparator = decimalSeparator == ',' ? '.' : ',';
            normalised = cleaned.replace(String.valueOf(groupSeparator), "")
                    .replace(decimalSeparator, '.');
        } else if (lastComma >= 0 || lastDot >= 0) {
            char separator = lastComma >= 0 ? ',' : '.';
            normalised = looksLikeGrouping(cleaned, separator)
                    ? cleaned.replace(String.valueOf(separator), "")
                    : cleaned.replace(separator, '.');
        } else {
            normalised = cleaned;
        }

        try {
            BigDecimal parsed = new BigDecimal(normalised);
            return negative ? parsed.negate() : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean looksLikeGrouping(String value, char separator) {
        String[] parts = value.split(Pattern.quote(String.valueOf(separator)), -1);
        if (parts.length < 2) {
            return false;
        }
        // More than one separator can only be grouping.
        if (parts.length > 2) {
            return allThreeDigits(parts, 1);
        }
        // A single separator is grouping only if exactly three digits follow it.
        return parts[1].length() == 3 && parts[0].length() <= 3 && !parts[0].isEmpty();
    }

    private static boolean allThreeDigits(String[] parts, int from) {
        for (int i = from; i < parts.length; i++) {
            if (parts[i].length() != 3) {
                return false;
            }
        }
        return true;
    }
}
