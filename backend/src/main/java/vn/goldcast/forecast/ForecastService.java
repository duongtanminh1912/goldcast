package vn.goldcast.forecast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.goldcast.api.InsufficientHistoryException;
import vn.goldcast.api.dto.BacktestDto;
import vn.goldcast.api.dto.ForecastDto;
import vn.goldcast.api.dto.InstrumentDto;
import vn.goldcast.config.AppProperties;
import vn.goldcast.domain.ForecastPoint;
import vn.goldcast.domain.ForecastRun;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.market.Amounts;
import vn.goldcast.market.PriceSeriesService;
import vn.goldcast.market.TradingCalendar;
import vn.goldcast.repository.ForecastRunRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces forecasts, and the evidence needed to judge them.
 *
 * <p>The order of operations is deliberate: backtest first, forecast second. Accuracy is
 * measured on data the model has not seen, then the same model is refitted on the full
 * history to produce the forward path. Prediction intervals come from those measured
 * errors, so the band shown on the chart reflects how wrong this model has actually been
 * rather than how well it fits its own training data.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final PriceSeriesService series;
    private final ForecastRunRepository runs;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ForecastService(PriceSeriesService series,
                           ForecastRunRepository runs,
                           AppProperties properties,
                           ObjectMapper objectMapper) {
        this.series = series;
        this.runs = runs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ForecastDto forecast(String code, ForecastModel requested, Integer requestedHorizon,
                                boolean persist) {
        Instrument instrument = series.requireInstrument(code);
        int horizon = resolveHorizon(requestedHorizon);
        ForecastModel wanted = requested != null ? requested : ForecastModel.AUTO;

        List<PricePoint> history = loadHistory(instrument);
        double[] closes = PriceSeriesService.closes(history);
        LocalDate lastDate = history.get(history.size() - 1).getObservedOn();
        BigDecimal lastClose = history.get(history.size() - 1).getClosePrice();

        Backtester backtester = new Backtester(properties.forecast().backtestMaxOrigins());
        int minTrain = Math.min(properties.forecast().backtestMinTrain(), closes.length / 2);

        Selection selection = select(wanted, closes, horizon, backtester, minTrain);
        Forecaster forecaster = selection.forecaster();
        BacktestResult accuracy = selection.backtest();

        PointForecast forecast = forecaster.forecast(closes, horizon);
        double fallbackSigma = IntervalEstimator.diffSigma(closes);
        List<LocalDate> targetDates =
                TradingCalendar.futureDates(lastDate, horizon, instrument.getKind());

        int scale = instrument.getUnit().displayScale();
        double[] values = forecast.values();
        List<ForecastDto.Step> steps = new ArrayList<>(horizon);

        for (int i = 0; i < horizon; i++) {
            PredictionInterval interval =
                    IntervalEstimator.estimate(values[i], i + 1, accuracy, fallbackSigma);
            steps.add(new ForecastDto.Step(
                    i + 1,
                    targetDates.get(i),
                    Amounts.of(values[i], scale),
                    Amounts.of(interval.lower80(), scale),
                    Amounts.of(interval.upper80(), scale),
                    Amounts.of(interval.lower95(), scale),
                    Amounts.of(interval.upper95(), scale),
                    Amounts.percent((values[i] - lastClose.doubleValue())
                            / lastClose.doubleValue() * 100.0)));
        }

        boolean synthetic = PriceSeriesService.containsSynthetic(history);
        List<String> warnings = buildWarnings(closes.length, horizon, accuracy, synthetic);

        ForecastDto dto = new ForecastDto(
                InstrumentDto.from(instrument),
                forecaster.model().name(),
                forecaster.model().displayName(),
                forecaster.model().description(),
                horizon,
                closes.length,
                Amounts.scale(lastClose, scale),
                lastDate,
                OffsetDateTime.now(),
                steps,
                toAccuracy(accuracy),
                toHorizonAccuracy(accuracy),
                selection.scores(),
                finiteParams(forecast.params()),
                warnings,
                synthetic);

        if (persist) {
            persist(instrument, forecaster, forecast, accuracy, steps, closes.length,
                    lastClose, lastDate);
        }
        return dto;
    }

    /** Rolling-origin comparison of every candidate model, for the methodology page. */
    @Transactional(readOnly = true)
    public BacktestDto backtest(String code, Integer requestedHorizon) {
        Instrument instrument = series.requireInstrument(code);
        int horizon = resolveHorizon(requestedHorizon);

        List<PricePoint> history = loadHistory(instrument);
        double[] closes = PriceSeriesService.closes(history);

        Backtester backtester = new Backtester(properties.forecast().backtestMaxOrigins());
        int minTrain = Math.min(properties.forecast().backtestMinTrain(), closes.length / 2);

        List<BacktestDto.ModelResult> results = new ArrayList<>();
        String best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (Forecaster candidate : Forecasters.candidates(closes.length, horizon)) {
            BacktestResult result = backtester.run(closes, candidate, horizon, minTrain);
            if (!result.hasData()) {
                continue;
            }

            List<BacktestDto.HorizonRow> rows = result.byHorizon().stream()
                    .map(accuracy -> new BacktestDto.HorizonRow(
                            accuracy.step(),
                            Amounts.nullIfNotFinite(accuracy.metrics().mae()),
                            Amounts.nullIfNotFinite(accuracy.metrics().rmse()),
                            Amounts.percent(accuracy.metrics().mape()),
                            accuracy.metrics().sample()))
                    .toList();

            Metrics overall = result.overall();
            results.add(new BacktestDto.ModelResult(
                    candidate.model().name(),
                    candidate.model().displayName(),
                    candidate.model().description(),
                    Amounts.nullIfNotFinite(overall.mae()),
                    Amounts.nullIfNotFinite(overall.rmse()),
                    Amounts.percent(overall.mape()),
                    Amounts.nullIfNotFinite(overall.mase()),
                    overall.sample(),
                    result.origins(),
                    overall.beatsNaive(),
                    rows));

            double score = scoreOf(result);
            if (score < bestScore) {
                bestScore = score;
                best = candidate.model().name();
            }
        }

        results.sort(Comparator.comparing(
                result -> result.mase() == null ? Double.MAX_VALUE : result.mase()));

        return new BacktestDto(
                InstrumentDto.from(instrument),
                horizon,
                closes.length,
                minTrain,
                List.copyOf(results),
                best,
                "Mỗi mô hình được khớp lại tại từng điểm gốc chỉ với dữ liệu có trước thời điểm đó, "
                        + "rồi chấm điểm trên phần dữ liệu phía sau. MASE < 1 nghĩa là mô hình tốt hơn "
                        + "giả định 'ngày mai bằng hôm nay'.");
    }

    /**
     * Picks the model to use.
     *
     * <p>For {@link ForecastModel#AUTO} every candidate is backtested and the lowest MASE
     * wins, falling back to RMSE when MASE cannot be computed. Candidates are evaluated in
     * catalogue order and ties go to whichever came first, which favours the simpler model.
     */
    private Selection select(ForecastModel wanted, double[] closes, int horizon,
                             Backtester backtester, int minTrain) {

        List<Forecaster> candidates = wanted == ForecastModel.AUTO
                ? Forecasters.candidates(closes.length, horizon)
                : List.of(Forecasters.create(wanted));

        Forecaster chosen = null;
        BacktestResult chosenResult = null;
        double bestScore = Double.POSITIVE_INFINITY;
        List<Scored> scored = new ArrayList<>();

        for (Forecaster candidate : candidates) {
            if (closes.length < candidate.minObservations()) {
                continue;
            }
            BacktestResult result = backtester.run(closes, candidate, horizon, minTrain);
            scored.add(new Scored(candidate, result));

            double score = scoreOf(result);
            if (score < bestScore) {
                bestScore = score;
                chosen = candidate;
                chosenResult = result;
            }
        }

        if (chosen == null) {
            // Nothing could be scored — the series is short. Naive always fits.
            chosen = new NaiveForecaster();
            chosenResult = backtester.run(closes, chosen, horizon, minTrain);
            scored.add(new Scored(chosen, chosenResult));
            log.debug("Không chấm điểm được mô hình nào, dùng NAIVE");
        }

        final ForecastModel selectedModel = chosen.model();
        List<ForecastDto.ModelScore> scores = scored.stream()
                .map(entry -> new ForecastDto.ModelScore(
                        entry.forecaster().model().name(),
                        entry.forecaster().model().displayName(),
                        Amounts.nullIfNotFinite(entry.result().overall().mase()),
                        Amounts.nullIfNotFinite(entry.result().overall().rmse()),
                        Amounts.percent(entry.result().overall().mape()),
                        entry.forecaster().model() == selectedModel))
                .toList();

        return new Selection(chosen, chosenResult, scores);
    }

    /** Lower is better. MASE first, RMSE as a tiebreak, unscoreable models last. */
    private static double scoreOf(BacktestResult result) {
        if (result == null || !result.hasData()) {
            return Double.POSITIVE_INFINITY;
        }
        double mase = result.overall().mase();
        if (Double.isFinite(mase)) {
            return mase;
        }
        double rmse = result.overall().rmse();
        return Double.isFinite(rmse) ? rmse : Double.POSITIVE_INFINITY;
    }

    private List<PricePoint> loadHistory(Instrument instrument) {
        List<PricePoint> history =
                series.recent(instrument.getId(), properties.forecast().maxTrainWindow());
        int minHistory = properties.forecast().minHistory();
        if (history.size() < minHistory) {
            throw new InsufficientHistoryException(instrument.getCode(), history.size(), minHistory);
        }
        return history;
    }

    private int resolveHorizon(Integer requested) {
        int horizon = requested != null ? requested : properties.forecast().defaultHorizon();
        if (horizon < 1 || horizon > properties.forecast().maxHorizon()) {
            throw new IllegalArgumentException(
                    "horizon phải nằm trong khoảng 1.." + properties.forecast().maxHorizon()
                            + ", nhận được " + horizon);
        }
        return horizon;
    }

    private List<String> buildWarnings(int trainSize, int horizon, BacktestResult accuracy,
                                       boolean synthetic) {
        List<String> warnings = new ArrayList<>();

        if (synthetic) {
            warnings.add("Dự báo này dựa trên dữ liệu mô phỏng, không phải giá thị trường thật. "
                    + "Hãy cấu hình nguồn dữ liệu thật trước khi diễn giải kết quả.");
        }
        if (trainSize < 90) {
            warnings.add("Lịch sử mới có " + trainSize + " quan sát — quá ngắn để ước lượng ổn định. "
                    + "Kết quả sẽ thay đổi đáng kể khi dữ liệu dày lên.");
        }
        if (accuracy != null && accuracy.hasData() && !accuracy.overall().beatsNaive()) {
            warnings.add("Mô hình này không vượt được baseline naive (MASE ≥ 1) trên chính chuỗi dữ "
                    + "liệu của nó. Nói cách khác, giá vàng ở khung thời gian này gần với bước đi "
                    + "ngẫu nhiên và điểm dự báo không đáng tin hơn giả định giá đứng yên.");
        }
        if (horizon > 30) {
            warnings.add("Sai số tăng theo căn bậc hai của số ngày dự báo. Ở tầm " + horizon
                    + " ngày, khoảng tin cậy rộng hơn giá trị điểm rất nhiều.");
        }
        warnings.add("Dự báo là ngoại suy thống kê từ giá quá khứ. Nó không mô hình hoá chính sách "
                + "tiền tệ, biến động địa chính trị hay can thiệp thị trường vàng trong nước.");
        return warnings;
    }

    private static ForecastDto.Accuracy toAccuracy(BacktestResult result) {
        if (result == null || !result.hasData()) {
            return new ForecastDto.Accuracy(null, null, null, null, 0, 0, false);
        }
        Metrics overall = result.overall();
        return new ForecastDto.Accuracy(
                Amounts.nullIfNotFinite(overall.mae()),
                Amounts.nullIfNotFinite(overall.rmse()),
                Amounts.percent(overall.mape()),
                Amounts.nullIfNotFinite(overall.mase()),
                overall.sample(),
                result.origins(),
                overall.beatsNaive());
    }

    private static List<ForecastDto.HorizonAccuracy> toHorizonAccuracy(BacktestResult result) {
        if (result == null || !result.hasData()) {
            return List.of();
        }
        return result.byHorizon().stream()
                .map(accuracy -> new ForecastDto.HorizonAccuracy(
                        accuracy.step(),
                        Amounts.nullIfNotFinite(accuracy.metrics().mae()),
                        Amounts.nullIfNotFinite(accuracy.metrics().rmse()),
                        Amounts.percent(accuracy.metrics().mape()),
                        accuracy.metrics().sample()))
                .toList();
    }

    private void persist(Instrument instrument, Forecaster forecaster, PointForecast forecast,
                         BacktestResult accuracy, List<ForecastDto.Step> steps, int trainSize,
                         BigDecimal lastClose, LocalDate lastDate) {
        try {
            ForecastRun run = new ForecastRun(instrument, forecaster.model().name(),
                    steps.size(), trainSize, lastClose, lastDate);
            run.setParams(writeParams(finiteParams(forecast.params())));

            if (accuracy != null && accuracy.hasData()) {
                Metrics overall = accuracy.overall();
                run.setMae(Amounts.of(overall.mae(), 6));
                run.setRmse(Amounts.of(overall.rmse(), 6));
                run.setMape(Amounts.of(overall.mape(), 6));
                run.setMase(Amounts.of(overall.mase(), 6));
            }

            for (ForecastDto.Step step : steps) {
                run.addPoint(new ForecastPoint(step.step(), step.date(), step.value(),
                        step.lower80(), step.upper80(), step.lower95(), step.upper95()));
            }
            runs.save(run);
        } catch (RuntimeException ex) {
            // Storing the run is for later auditing; failing to store it must not fail the request.
            log.warn("Không lưu được forecast run cho {}: {}", instrument.getCode(), ex.getMessage());
        }
    }

    /**
     * Drops any non-finite parameter before it reaches the response.
     *
     * <p>Jackson serialises {@code NaN} and {@code Infinity} as bare tokens, which are not
     * valid JSON — a single degenerate fit would make the whole payload unparseable in the
     * browser. Omitting the parameter costs nothing; breaking the response costs the page.
     */
    private static Map<String, Double> finiteParams(Map<String, Double> params) {
        Map<String, Double> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : params.entrySet()) {
            Double value = entry.getValue();
            if (value != null && Double.isFinite(value)) {
                clean.put(entry.getKey(), value);
            }
        }
        return clean;
    }

    private String writeParams(Map<String, Double> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private record Selection(Forecaster forecaster, BacktestResult backtest,
                             List<ForecastDto.ModelScore> scores) {}

    private record Scored(Forecaster forecaster, BacktestResult result) {}
}
