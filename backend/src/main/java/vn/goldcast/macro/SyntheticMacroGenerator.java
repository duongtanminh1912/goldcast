package vn.goldcast.macro;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic stand-in macro history for offline demos and the isolated G2 environment,
 * where external providers are switched off. Used only when a series is empty and seeding
 * is enabled; rows are stored with source {@code synthetic} and forecasts that use them
 * say so.
 */
@Component
public class SyntheticMacroGenerator {

    public static final String SOURCE = "synthetic";

    private static final long SEED = 20_261_117L;

    public List<MacroObservation> generate(MacroSeries series, LocalDate today, int days) {
        Random random = new Random(SEED + series.ordinal());
        List<MacroObservation> out = new ArrayList<>();
        double value = switch (series) {
            case REAL_YIELD_10Y -> 1.80;
            case USD_BROAD -> 120.0;
        };
        for (LocalDate date = today.minusDays(days); !date.isAfter(today); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            value = switch (series) {
                case REAL_YIELD_10Y -> Math.max(-1.5, Math.min(4.0, value + random.nextGaussian() * 0.04));
                case USD_BROAD -> value * Math.exp(random.nextGaussian() * 0.003);
            };
            out.add(new MacroObservation(date, Math.round(value * 10_000) / 10_000.0));
        }
        return out;
    }
}
