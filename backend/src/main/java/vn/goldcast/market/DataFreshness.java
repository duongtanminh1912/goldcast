package vn.goldcast.market;

import vn.goldcast.domain.InstrumentKind;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Daily observations: allow two publication days of lag, excluding FX/spot weekends.
 * Holidays are deliberately not guessed; operators must investigate holiday warnings. */
public final class DataFreshness {
    private DataFreshness() {}

    public static String warning(String code, InstrumentKind kind, LocalDate observed, LocalDate today) {
        if (observed == null) return code + ": chưa có dữ liệu quan sát.";
        long days = ChronoUnit.DAYS.between(observed, today);
        if (days < 0) return code + ": ngày quan sát " + observed + " nằm trong tương lai.";
        long lag = days;
        if (kind.businessDaysOnly()) {
            lag = (days / 7) * 5;
            for (long offset = (days / 7) * 7 + 1; offset <= days; offset++) {
                if (!TradingCalendar.isWeekend(observed.plusDays(offset))) lag++;
            }
        }
        return lag > 2 ? code + ": dữ liệu cũ, quan sát gần nhất " + observed
                + ", chậm " + lag + " ngày công bố (chưa tính ngày lễ)." : null;
    }
}
