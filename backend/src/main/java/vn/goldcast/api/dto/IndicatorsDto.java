package vn.goldcast.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Technical indicators over a price series.
 *
 * <p>Series are parallel arrays aligned with {@code dates}, with {@code null} wherever the
 * indicator is not yet defined, so a chart can plot them without index arithmetic.
 *
 * <p>The {@code signal} field describes what the indicators currently say. It is a
 * description of the data, not a recommendation, and it is phrased that way on purpose.
 */
public record IndicatorsDto(
        InstrumentDto instrument,
        // Serialised as ISO dates by the JSR-310 module; @JsonFormat on a List would apply
        // to the container rather than its elements, so it is deliberately absent here.
        List<LocalDate> dates,
        List<Double> close,
        List<Double> sma20,
        List<Double> sma50,
        List<Double> ema20,
        List<Double> rsi14,
        List<Double> bollingerUpper,
        List<Double> bollingerMiddle,
        List<Double> bollingerLower,
        Summary summary) {

    /**
     * @param trend          one of UP, DOWN, SIDEWAYS — based on the SMA20/SMA50 relationship
     * @param rsiState       OVERBOUGHT above 70, OVERSOLD below 30, NEUTRAL in between
     * @param volatilityPct  annualised volatility of daily log returns, in percent
     */
    public record Summary(
            Double latestClose,
            Double sma20,
            Double sma50,
            Double rsi14,
            String trend,
            String rsiState,
            Double change7dPercent,
            Double change30dPercent,
            Double volatilityPct,
            String note) {}
}
