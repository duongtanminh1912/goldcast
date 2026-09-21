package vn.goldcast.ingest;

import org.springframework.stereotype.Component;
import vn.goldcast.domain.Instrument;
import vn.goldcast.market.GoldUnits;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic synthetic history, used only when a series is empty and no upstream feed
 * could be reached — typically a first run with no internet, or a demo environment.
 *
 * <p>Two properties matter. It is <em>deterministic</em>, so the same container always
 * produces the same numbers and a screenshot stays reproducible. And it is <em>coherent
 * across instruments</em>: the domestic series is derived from the synthetic world price
 * and FX rate plus a drifting premium, so the conversion and premium figures the app
 * displays remain internally consistent rather than nonsense.
 *
 * <p>Every row it writes is tagged {@link #SOURCE}, and the API reports that tag, so a
 * synthetic series can never quietly be mistaken for real market data.
 */
@Component
public class SyntheticSeedGenerator {

    public static final String SOURCE = "synthetic";

    /** Fixed so that repeated runs reproduce the same series exactly. */
    private static final long SEED = 20_260_921L;

    private static final double WORLD_START_USD_PER_OZ = 1_950.0;
    private static final double WORLD_DAILY_DRIFT = 0.00035;
    private static final double WORLD_DAILY_VOL = 0.0085;

    private static final double FX_START = 23_450.0;
    private static final double FX_DAILY_DRIFT = 0.00008;
    private static final double FX_DAILY_VOL = 0.0007;

    private static final double PREMIUM_START = 1.10;
    private static final double PREMIUM_DAILY_VOL = 0.0025;
    private static final double PREMIUM_MIN = 1.02;
    private static final double PREMIUM_MAX = 1.35;

    /** Dealer spread as a fraction of the sell price. */
    private static final double SPREAD_FRACTION = 0.018;

    /**
     * Builds a coherent set of series for the instruments given.
     *
     * @param instruments the instruments to generate for; only recognised kinds are filled
     * @param days        how many calendar days back to generate
     */
    public List<ProviderQuote> generate(List<Instrument> instruments, int days) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(days);

        Random random = new Random(SEED);

        Map<LocalDate, Double> world = new HashMap<>();
        Map<LocalDate, Double> fx = new HashMap<>();
        Map<LocalDate, Double> premium = new HashMap<>();

        double worldLevel = WORLD_START_USD_PER_OZ;
        double fxLevel = FX_START;
        double premiumLevel = PREMIUM_START;

        double lastWorld = worldLevel;
        double lastFx = fxLevel;

        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            boolean businessDay = isBusinessDay(date);

            if (businessDay) {
                worldLevel *= Math.exp(WORLD_DAILY_DRIFT + WORLD_DAILY_VOL * random.nextGaussian());
                fxLevel *= Math.exp(FX_DAILY_DRIFT + FX_DAILY_VOL * random.nextGaussian());
                world.put(date, worldLevel);
                fx.put(date, fxLevel);
                lastWorld = worldLevel;
                lastFx = fxLevel;
            }

            // The domestic premium moves every day, including weekends when dealers still quote.
            premiumLevel = clamp(premiumLevel + PREMIUM_DAILY_VOL * random.nextGaussian(),
                    PREMIUM_MIN, PREMIUM_MAX);
            premium.put(date, premiumLevel);

            if (!businessDay) {
                world.put(date, lastWorld);
                fx.put(date, lastFx);
            }
        }

        List<ProviderQuote> out = new ArrayList<>();
        for (Instrument instrument : instruments) {
            switch (instrument.getKind()) {
                case SPOT_GOLD -> emit(out, instrument, start, today, true,
                        date -> BigDecimal.valueOf(world.get(date)).setScale(2, RoundingMode.HALF_UP));
                case FX -> emit(out, instrument, start, today, true,
                        date -> BigDecimal.valueOf(fx.get(date)).setScale(0, RoundingMode.HALF_UP));
                case VN_GOLD -> emitTwoWay(out, instrument, start, today, world, fx, premium);
            }
        }
        return out;
    }

    private void emit(List<ProviderQuote> sink, Instrument instrument, LocalDate start,
                      LocalDate end, boolean businessDaysOnly, ValueAt valueAt) {
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (businessDaysOnly && !isBusinessDay(date)) {
                continue;
            }
            sink.add(ProviderQuote.single(instrument.getCode(), date, valueAt.apply(date)));
        }
    }

    private void emitTwoWay(List<ProviderQuote> sink, Instrument instrument, LocalDate start,
                            LocalDate end, Map<LocalDate, Double> world, Map<LocalDate, Double> fx,
                            Map<LocalDate, Double> premium) {
        // Gold rings carry a smaller premium than bullion bars in the real market.
        double productFactor = instrument.getCode().toUpperCase().contains("RING") ? 0.94 : 1.0;
        // Hanoi quotes sit a touch above Ho Chi Minh City on the sell side.
        double regionFactor = "SJC_HN".equalsIgnoreCase(instrument.getCode()) ? 1.001 : 1.0;

        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Double worldValue = world.get(date);
            Double fxValue = fx.get(date);
            Double premiumValue = premium.get(date);
            if (worldValue == null || fxValue == null || premiumValue == null) {
                continue;
            }

            double baseVndPerTael = GoldUnits.worldToVndPerTael(worldValue, fxValue);
            double sell = baseVndPerTael * premiumValue * productFactor * regionFactor;
            double buy = sell * (1 - SPREAD_FRACTION);

            sink.add(ProviderQuote.twoWay(
                    instrument.getCode(),
                    date,
                    round(buy),
                    round(sell)));
        }
    }

    private static BigDecimal round(double value) {
        // Dealers quote in whole thousands of đồng.
        return BigDecimal.valueOf(Math.round(value / 1000.0) * 1000L);
    }

    private static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    private interface ValueAt {
        BigDecimal apply(LocalDate date);
    }
}
