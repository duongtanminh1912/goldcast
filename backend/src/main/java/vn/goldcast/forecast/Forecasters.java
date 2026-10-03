package vn.goldcast.forecast;

import java.util.ArrayList;
import java.util.List;

/** Builds forecaster instances and decides which ones a given history can support. */
public final class Forecasters {

    public static final int DEFAULT_SMA_WINDOW = 7;
    public static final int DEFAULT_AR_ORDER = 5;

    private Forecasters() {}

    public static Forecaster create(ForecastModel model) {
        return create(model, DEFAULT_SMA_WINDOW, DEFAULT_AR_ORDER);
    }

    public static Forecaster create(ForecastModel model, int smaWindow, int arOrder) {
        return switch (model) {
            case NAIVE -> new NaiveForecaster();
            case DRIFT -> new DriftForecaster();
            case SMA -> new MovingAverageForecaster(smaWindow);
            case HOLT_DAMPED -> new HoltDampedForecaster();
            case AR_DIFF -> new ArDiffForecaster(arOrder);
            case AUTO -> throw new IllegalArgumentException(
                    "AUTO không phải một mô hình cụ thể — dùng Forecasters.candidates()");
        };
    }

    /**
     * Every fittable model whose data requirement the history satisfies, given that a
     * backtest also needs room for at least one train/test split.
     *
     * @param seriesLength number of observations available
     * @param horizon      steps the forecast must cover
     */
    public static List<Forecaster> candidates(int seriesLength, int horizon) {
        List<Forecaster> out = new ArrayList<>();
        for (ForecastModel model : ForecastModel.fittable()) {
            Forecaster forecaster = create(model);
            if (seriesLength >= forecaster.minObservations() + horizon) {
                out.add(forecaster);
            }
        }
        if (out.isEmpty()) {
            out.add(new NaiveForecaster());
        }
        return out;
    }
}
