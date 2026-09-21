package vn.goldcast.analytics;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.goldcast.api.InsufficientHistoryException;
import vn.goldcast.api.dto.IndicatorsDto;
import vn.goldcast.api.dto.InstrumentDto;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.domain.PricePoint;
import vn.goldcast.market.Amounts;
import vn.goldcast.market.PriceSeriesService;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes technical indicators for an instrument and describes what they currently show.
 *
 * <p>The description is phrased as an observation about the data ("SMA20 đang nằm trên
 * SMA50"), never as a recommendation. The difference matters: one is a fact about a
 * number, the other is financial advice this application is in no position to give.
 */
@Service
@Transactional(readOnly = true)
public class IndicatorService {

    /** Bollinger and SMA50 need at least this much history to mean anything. */
    private static final int MIN_POINTS = 50;

    private static final int SMA_SHORT = 20;
    private static final int SMA_LONG = 50;
    private static final int EMA_WINDOW = 20;
    private static final int RSI_PERIOD = 14;
    private static final int BOLLINGER_WINDOW = 20;
    private static final double BOLLINGER_K = 2.0;

    /** Dealer quotes print daily, world spot only on weekdays. */
    private static final int TRADING_DAYS_VN = 365;
    private static final int TRADING_DAYS_WORLD = 252;

    private final PriceSeriesService series;

    public IndicatorService(PriceSeriesService series) {
        this.series = series;
    }

    public IndicatorsDto compute(String code, int lookback) {
        Instrument instrument = series.requireInstrument(code);
        List<PricePoint> points = series.recent(instrument.getId(), Math.max(lookback, MIN_POINTS));

        if (points.size() < MIN_POINTS) {
            throw new InsufficientHistoryException(instrument.getCode(), points.size(), MIN_POINTS);
        }

        double[] closes = PriceSeriesService.closes(points);

        double[] sma20 = Indicators.sma(closes, SMA_SHORT);
        double[] sma50 = Indicators.sma(closes, SMA_LONG);
        double[] ema20 = Indicators.ema(closes, EMA_WINDOW);
        double[] rsi14 = Indicators.rsi(closes, RSI_PERIOD);
        Indicators.Bands bands = Indicators.bollinger(closes, BOLLINGER_WINDOW, BOLLINGER_K);

        int last = closes.length - 1;
        int tradingDays = instrument.getKind() == InstrumentKind.VN_GOLD
                ? TRADING_DAYS_VN : TRADING_DAYS_WORLD;

        IndicatorsDto.Summary summary = new IndicatorsDto.Summary(
                round(closes[last], instrument),
                round(sma20[last], instrument),
                round(sma50[last], instrument),
                Amounts.percent(rsi14[last]),
                trendOf(sma20[last], sma50[last]),
                rsiStateOf(rsi14[last]),
                Amounts.percent(PriceSeriesService.changePercentOverDays(points, 7)),
                Amounts.percent(PriceSeriesService.changePercentOverDays(points, 30)),
                Amounts.percent(Indicators.annualisedVolatility(closes, tradingDays)),
                "Các chỉ báo mô tả trạng thái hiện tại của chuỗi giá, không phải khuyến nghị mua bán.");

        return new IndicatorsDto(
                InstrumentDto.from(instrument),
                PriceSeriesService.dates(points),
                toList(closes, instrument),
                toList(sma20, instrument),
                toList(sma50, instrument),
                toList(ema20, instrument),
                toList(rsi14, null),
                toList(bands.upper(), instrument),
                toList(bands.middle(), instrument),
                toList(bands.lower(), instrument),
                summary);
    }

    /**
     * Describes where the short average sits relative to the long one.
     *
     * <p>A 0.5% dead band keeps the label from flickering between UP and DOWN every time
     * the two averages brush against each other.
     */
    private static String trendOf(double shortMa, double longMa) {
        if (Double.isNaN(shortMa) || Double.isNaN(longMa) || longMa == 0) {
            return "UNKNOWN";
        }
        double ratio = shortMa / longMa;
        if (ratio > 1.005) {
            return "UP";
        }
        if (ratio < 0.995) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }

    private static String rsiStateOf(double rsi) {
        if (Double.isNaN(rsi)) {
            return "UNKNOWN";
        }
        if (rsi >= 70) {
            return "OVERBOUGHT";
        }
        if (rsi <= 30) {
            return "OVERSOLD";
        }
        return "NEUTRAL";
    }

    /** NaN becomes {@code null} so the chart simply has no point there. */
    private static List<Double> toList(double[] values, Instrument instrument) {
        List<Double> out = new ArrayList<>(values.length);
        for (double value : values) {
            out.add(round(value, instrument));
        }
        return out;
    }

    private static Double round(double value, Instrument instrument) {
        if (!Double.isFinite(value)) {
            return null;
        }
        if (instrument == null) {
            return Math.round(value * 100.0) / 100.0;
        }
        int scale = instrument.getUnit().displayScale();
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
