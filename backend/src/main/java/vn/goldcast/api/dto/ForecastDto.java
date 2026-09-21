package vn.goldcast.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * A forecast together with everything needed to judge it.
 *
 * <p>The accuracy block and the warnings are not decoration. A point forecast shown
 * without its measured error, and without saying when the history behind it is too short
 * or partly synthetic, is a number that looks far more authoritative than it is.
 */
public record ForecastDto(
        InstrumentDto instrument,
        String model,
        String modelLabel,
        String modelDescription,
        int horizon,
        int trainSize,
        BigDecimal lastClose,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate lastCloseOn,
        OffsetDateTime generatedAt,
        List<Step> points,
        Accuracy accuracy,
        List<HorizonAccuracy> accuracyByHorizon,
        List<ModelScore> modelScores,
        Map<String, Double> params,
        List<String> warnings,
        boolean basedOnSyntheticData) {

    /**
     * One forecast step.
     *
     * @param changeFromLastPercent move relative to the last observed close, in percent —
     *                              the number most readers actually want
     */
    public record Step(
            int step,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
            BigDecimal value,
            BigDecimal lower80,
            BigDecimal upper80,
            BigDecimal lower95,
            BigDecimal upper95,
            Double changeFromLastPercent) {}

    /**
     * Rolling-origin accuracy of this model on this instrument.
     *
     * @param beatsNaive whether MASE is below 1 — if it is not, the model adds nothing
     *                   over assuming tomorrow equals today
     */
    public record Accuracy(
            Double mae,
            Double rmse,
            Double mape,
            Double mase,
            int sampleSize,
            int origins,
            boolean beatsNaive) {}

    /** Accuracy at one specific number of steps ahead; error grows with distance. */
    public record HorizonAccuracy(int step, Double mae, Double rmse, Double mape, int sampleSize) {}

    /**
     * How every candidate model scored during model selection.
     *
     * @param selected true for the model whose forecast is being returned
     */
    public record ModelScore(String model, String modelLabel, Double mase, Double rmse,
                             Double mape, boolean selected) {}
}
