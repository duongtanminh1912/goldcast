package vn.goldcast.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The dashboard payload: world gold, the FX rate, and every domestic quote expressed both
 * as the dealer publishes it and as a premium over the converted world price.
 *
 * <p>That premium is the point of the page. In Vietnam it is large, it moves on domestic
 * policy rather than on the world market, and a site that shows only the headline domestic
 * price hides the single most useful fact about it.
 */
public record MarketSummaryDto(
        OffsetDateTime generatedAt,
        WorldQuote world,
        FxQuote fx,
        List<DomesticQuote> domestic,
        Conversion conversion,
        List<String> warnings,
        String disclaimer) {

    public record WorldQuote(
            InstrumentDto instrument,
            BigDecimal usdPerOunce,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate asOf,
            Double change1dPercent,
            Double change7dPercent,
            Double change30dPercent,
            String source) {}

    public record FxQuote(
            InstrumentDto instrument,
            BigDecimal vndPerUsd,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate asOf,
            Double change30dPercent,
            String source) {}

    /**
     * A dealer quote, next to what the same gold would cost at world parity.
     *
     * @param premiumVnd     domestic sell price minus the world-equivalent price
     * @param premiumPercent the same, relative to the world-equivalent price
     * @param spreadVnd      the dealer's own buy/sell spread
     */
    public record DomesticQuote(
            InstrumentDto instrument,
            BigDecimal buy,
            BigDecimal sell,
            BigDecimal spreadVnd,
            Double spreadPercent,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate asOf,
            Double change1dPercent,
            Double change7dPercent,
            Double change30dPercent,
            BigDecimal worldEquivalentVnd,
            BigDecimal premiumVnd,
            Double premiumPercent,
            String source) {}

    /**
     * The unit conversion itself, exposed so the arithmetic on the page can be checked
     * rather than taken on trust.
     */
    public record Conversion(
            BigDecimal worldVndPerTael,
            BigDecimal worldVndPerChi,
            BigDecimal worldVndPerGram,
            double taelInGrams,
            double troyOunceInGrams,
            String formula) {}
}
