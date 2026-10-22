package vn.goldcast.market.live;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;

/** A reference alerting window, not an exchange calendar or a provider market-open flag.
 * Suppress stale-price alerts over the usual NY 17–18h break and weekend. Holidays
 * and provider-specific closures still require operator review. DST follows New York. */
public final class GoldReferenceSession {
    private GoldReferenceSession() {}

    public static boolean expectsUpdates(Instant instant) {
        var time = instant.atZone(ZoneId.of("America/New_York"));
        DayOfWeek day = time.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY) return false;
        if (day == DayOfWeek.SUNDAY) return time.getHour() >= 18;
        if (day == DayOfWeek.FRIDAY) return time.getHour() < 17;
        return time.getHour() != 17;
    }
}
