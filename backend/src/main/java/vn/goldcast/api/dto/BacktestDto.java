package vn.goldcast.api.dto;

import java.util.List;

/**
 * Side-by-side rolling-origin accuracy for every candidate model on one instrument.
 *
 * <p>This is the page that keeps the rest of the application honest: it shows what each
 * model would have predicted historically and how far off it was, including whether it
 * beat simply assuming no change.
 */
public record BacktestDto(
        InstrumentDto instrument,
        int horizon,
        int seriesLength,
        int minTrain,
        List<ModelResult> models,
        String bestModel,
        String note) {

    public record ModelResult(
            String model,
            String modelLabel,
            String description,
            Double mae,
            Double rmse,
            Double mape,
            Double mase,
            int sampleSize,
            int origins,
            boolean beatsNaive,
            List<HorizonRow> byHorizon) {}

    public record HorizonRow(int step, Double mae, Double rmse, Double mape, int sampleSize) {}
}
