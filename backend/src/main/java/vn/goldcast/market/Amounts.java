package vn.goldcast.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rounding helpers, so every money figure leaves the API at a sensible scale. */
public final class Amounts {

    private Amounts() {}

    /** Rounds a double to a {@link BigDecimal}, or returns {@code null} for non-finite input. */
    public static BigDecimal of(double value, int scale) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal scale(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Converts a double to a {@link Double}, turning NaN and infinity into {@code null}. */
    public static Double nullIfNotFinite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /** Rounds a percentage to two decimals, or {@code null} if it is not a real number. */
    public static Double percent(double value) {
        if (!Double.isFinite(value)) {
            return null;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    public static Double percent(Double value) {
        return value == null ? null : percent(value.doubleValue());
    }

    /** Percentage change from {@code previous} to {@code current}. */
    public static Double changePercent(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) {
            return null;
        }
        return percent(current.subtract(previous)
                .divide(previous, 10, RoundingMode.HALF_UP)
                .doubleValue() * 100.0);
    }
}
