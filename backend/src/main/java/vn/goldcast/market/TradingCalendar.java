package vn.goldcast.market;

import vn.goldcast.domain.InstrumentKind;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps forecast steps onto real calendar dates.
 *
 * <p>World spot and FX only print on weekdays, so step 5 of a forecast is a week away,
 * not five days. Vietnamese dealers publish every day including weekends, so for those the
 * two coincide. Getting this wrong would label every forecast point with the wrong date.
 *
 * <p>Vietnamese public holidays (Tết in particular, when dealers close for several days)
 * are not modelled. Doing it properly needs a maintained holiday table rather than a
 * hard-coded guess, so the dates around a holiday are approximate and the API says so.
 */
public final class TradingCalendar {

    private TradingCalendar() {}

    /**
     * The {@code horizon} dates that follow {@code lastObserved}.
     *
     * @param lastObserved date of the most recent observation
     * @param horizon      how many future dates to produce
     * @param kind         decides whether weekends are skipped
     */
    public static List<LocalDate> futureDates(LocalDate lastObserved, int horizon, InstrumentKind kind) {
        if (lastObserved == null) {
            throw new IllegalArgumentException("lastObserved không được null");
        }
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon phải >= 1");
        }

        boolean skipWeekends = kind != null && kind.businessDaysOnly();
        List<LocalDate> out = new ArrayList<>(horizon);
        LocalDate cursor = lastObserved;

        while (out.size() < horizon) {
            cursor = cursor.plusDays(1);
            if (skipWeekends && isWeekend(cursor)) {
                continue;
            }
            out.add(cursor);
        }
        return out;
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
