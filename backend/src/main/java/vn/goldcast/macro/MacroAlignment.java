package vn.goldcast.macro;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/** Lines a macro series up against the dates of a price series. */
public final class MacroAlignment {

    private MacroAlignment() {}

    /**
     * For each price date, the most recent macro value observed on or before that date,
     * or {@code NaN} when there is none yet. Holidays in the macro calendar carry the
     * previous value forward; a value is never taken from a later date.
     */
    public static double[] asOf(List<LocalDate> dates, NavigableMap<LocalDate, Double> observations) {
        double[] out = new double[dates.size()];
        for (int i = 0; i < dates.size(); i++) {
            Map.Entry<LocalDate, Double> entry = observations.floorEntry(dates.get(i));
            out[i] = entry == null ? Double.NaN : entry.getValue();
        }
        return out;
    }
}
