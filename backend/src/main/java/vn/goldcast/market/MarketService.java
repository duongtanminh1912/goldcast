package vn.goldcast.market;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.goldcast.api.dto.InstrumentDto;
import vn.goldcast.api.dto.MarketSummaryDto;
import vn.goldcast.api.dto.PricePointDto;
import vn.goldcast.api.dto.SeriesDto;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;
import vn.goldcast.domain.PricePoint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds the dashboard view: world gold, the FX rate, and each domestic quote alongside
 * the world-equivalent price and the premium between them.
 */
@Service
@Transactional(readOnly = true)
public class MarketService {

    public static final String WORLD_CODE = "XAUUSD";
    public static final String FX_CODE = "USDVND";

    private static final String DISCLAIMER =
            "Số liệu chỉ mang tính tham khảo, được tổng hợp tự động từ nguồn công khai và có thể "
                    + "sai lệch so với giá giao dịch thực tế tại quầy. Đây không phải lời khuyên đầu tư.";

    /** Enough history to cover a 30-day comparison even across a long holiday gap. */
    private static final int LOOKBACK_POINTS = 90;

    private final PriceSeriesService series;

    public MarketService(PriceSeriesService series) {
        this.series = series;
    }

    public MarketSummaryDto summary() {
        List<String> warnings = new ArrayList<>();

        Instrument worldInstrument = series.requireInstrument(WORLD_CODE);
        Instrument fxInstrument = series.requireInstrument(FX_CODE);

        List<PricePoint> worldHistory = series.recent(worldInstrument.getId(), LOOKBACK_POINTS);
        List<PricePoint> fxHistory = series.recent(fxInstrument.getId(), LOOKBACK_POINTS);

        if (worldHistory.isEmpty() || fxHistory.isEmpty()) {
            warnings.add("Chưa có dữ liệu vàng thế giới hoặc tỷ giá — phần quy đổi tạm thời bị bỏ trống.");
        }
        if (PriceSeriesService.containsSynthetic(worldHistory)
                || PriceSeriesService.containsSynthetic(fxHistory)) {
            warnings.add("Một phần dữ liệu đang là dữ liệu mô phỏng (source = \"synthetic\"), "
                    + "không phải giá thị trường thật.");
        }

        MarketSummaryDto.WorldQuote world = worldHistory.isEmpty() ? null
                : buildWorld(worldInstrument, worldHistory);
        MarketSummaryDto.FxQuote fx = fxHistory.isEmpty() ? null
                : buildFx(fxInstrument, fxHistory);

        MarketSummaryDto.Conversion conversion = null;
        if (world != null && fx != null) {
            conversion = buildConversion(world.usdPerOunce(), fx.vndPerUsd());
        }

        List<MarketSummaryDto.DomesticQuote> domestic = new ArrayList<>();
        for (Instrument instrument : series.instrumentsOfKind(InstrumentKind.VN_GOLD)) {
            List<PricePoint> history = series.recent(instrument.getId(), LOOKBACK_POINTS);
            if (history.isEmpty()) {
                continue;
            }
            domestic.add(buildDomestic(instrument, history, worldInstrument, fxInstrument));
        }

        if (domestic.isEmpty()) {
            warnings.add("Chưa có dữ liệu giá vàng trong nước. Nguồn SJC chỉ công bố giá của ngày "
                    + "hiện tại nên chuỗi lịch sử sẽ dày lên dần theo thời gian chạy.");
        }

        return new MarketSummaryDto(
                OffsetDateTime.now(), world, fx, List.copyOf(domestic), conversion,
                List.copyOf(warnings), DISCLAIMER);
    }

    private MarketSummaryDto.WorldQuote buildWorld(Instrument instrument, List<PricePoint> history) {
        PricePoint latest = history.get(history.size() - 1);
        return new MarketSummaryDto.WorldQuote(
                InstrumentDto.from(instrument),
                Amounts.scale(latest.getClosePrice(), instrument.getUnit().displayScale()),
                latest.getObservedOn(),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 1)),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 7)),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 30)),
                latest.getSource());
    }

    private MarketSummaryDto.FxQuote buildFx(Instrument instrument, List<PricePoint> history) {
        PricePoint latest = history.get(history.size() - 1);
        return new MarketSummaryDto.FxQuote(
                InstrumentDto.from(instrument),
                Amounts.scale(latest.getClosePrice(), instrument.getUnit().displayScale()),
                latest.getObservedOn(),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 30)),
                latest.getSource());
    }

    private MarketSummaryDto.Conversion buildConversion(BigDecimal usdPerOunce, BigDecimal vndPerUsd) {
        double perTael = GoldUnits.worldToVndPerTael(usdPerOunce.doubleValue(), vndPerUsd.doubleValue());
        return new MarketSummaryDto.Conversion(
                Amounts.of(perTael, 0),
                Amounts.of(GoldUnits.taelToChi(perTael), 0),
                Amounts.of(GoldUnits.taelToGram(perTael), 0),
                GoldUnits.TAEL_IN_GRAMS,
                GoldUnits.TROY_OUNCE_IN_GRAMS,
                "VNĐ/lượng = USD/oz ÷ 31,1034768 × 37,5 × tỷ giá USD/VND");
    }

    /**
     * A domestic quote with its premium over world parity.
     *
     * <p>The world and FX values used are the ones in effect on the dealer's own quote
     * date, not the latest available. A Sunday dealer price compared against Monday's
     * world close would be comparing a price to information that did not exist yet.
     */
    private MarketSummaryDto.DomesticQuote buildDomestic(
            Instrument instrument, List<PricePoint> history,
            Instrument worldInstrument, Instrument fxInstrument) {

        PricePoint latest = history.get(history.size() - 1);
        LocalDate asOf = latest.getObservedOn();

        BigDecimal sell = latest.getSellPrice() != null ? latest.getSellPrice() : latest.getClosePrice();
        BigDecimal buy = latest.getBuyPrice();
        BigDecimal spread = (buy != null) ? sell.subtract(buy) : null;
        Double spreadPercent = (spread != null && sell.signum() != 0)
                ? Amounts.percent(spread.divide(sell, 10, java.math.RoundingMode.HALF_UP)
                        .doubleValue() * 100.0)
                : null;

        Optional<PricePoint> worldAt = series.asOf(worldInstrument.getId(), asOf);
        Optional<PricePoint> fxAt = series.asOf(fxInstrument.getId(), asOf);

        BigDecimal worldEquivalent = null;
        BigDecimal premiumVnd = null;
        Double premiumPercent = null;

        if (worldAt.isPresent() && fxAt.isPresent()) {
            double equivalent = GoldUnits.worldToVndPerTael(
                    worldAt.get().getClosePrice().doubleValue(),
                    fxAt.get().getClosePrice().doubleValue());
            GoldUnits.Premium premium = GoldUnits.premium(sell.doubleValue(), equivalent);
            worldEquivalent = Amounts.of(equivalent, 0);
            premiumVnd = Amounts.of(premium.amountVnd(), 0);
            premiumPercent = Amounts.percent(premium.percent());
        }

        int scale = instrument.getUnit().displayScale();
        return new MarketSummaryDto.DomesticQuote(
                InstrumentDto.from(instrument),
                Amounts.scale(buy, scale),
                Amounts.scale(sell, scale),
                Amounts.scale(spread, scale),
                spreadPercent,
                asOf,
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 1)),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 7)),
                Amounts.percent(PriceSeriesService.changePercentOverDays(history, 30)),
                worldEquivalent,
                premiumVnd,
                premiumPercent,
                latest.getSource());
    }

    /** A price history for one instrument, ready for charting. */
    public SeriesDto series(String code, LocalDate from, LocalDate to, Integer limit) {
        Instrument instrument = series.requireInstrument(code);

        List<PricePoint> points;
        if (from != null || to != null) {
            LocalDate start = from != null ? from : LocalDate.now().minusYears(10);
            LocalDate end = to != null ? to : LocalDate.now();
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("Tham số 'from' phải trước 'to'");
            }
            points = series.range(instrument.getId(), start, end);
        } else {
            points = series.recent(instrument.getId(), limit != null ? limit : 365);
        }

        int scale = instrument.getUnit().displayScale();
        List<PricePointDto> dtos = points.stream()
                .map(point -> new PricePointDto(
                        point.getObservedOn(),
                        Amounts.scale(point.getClosePrice(), scale),
                        Amounts.scale(point.getBuyPrice(), scale),
                        Amounts.scale(point.getSellPrice(), scale),
                        Amounts.scale(point.spread(), scale),
                        point.getSource()))
                .toList();

        BigDecimal latestClose = points.isEmpty() ? null
                : points.get(points.size() - 1).getClosePrice();
        BigDecimal first = points.isEmpty() ? null : points.get(0).getClosePrice();
        BigDecimal changeAbsolute = (latestClose != null && first != null)
                ? latestClose.subtract(first) : null;

        return new SeriesDto(
                InstrumentDto.from(instrument),
                dtos,
                points.isEmpty() ? null : points.get(0).getObservedOn(),
                points.isEmpty() ? null : points.get(points.size() - 1).getObservedOn(),
                dtos.size(),
                Amounts.scale(latestClose, scale),
                Amounts.scale(changeAbsolute, scale),
                Amounts.changePercent(latestClose, first),
                PriceSeriesService.sources(points),
                PriceSeriesService.containsSynthetic(points));
    }

    /** Instruments the API can serve, for populating menus. */
    public List<InstrumentDto> instruments() {
        return series.activeInstruments().stream().map(InstrumentDto::from).toList();
    }
}
