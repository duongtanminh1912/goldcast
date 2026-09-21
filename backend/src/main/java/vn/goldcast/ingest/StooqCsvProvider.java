package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vn.goldcast.config.AppProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Daily history from Stooq's CSV endpoint: world spot gold and the USD/VND rate.
 *
 * <p>A whole series arrives in one request, so backfilling years of history costs two
 * calls. All parsing lives in {@link StooqCsvParser}; this class only fetches.
 */
@Component
public class StooqCsvProvider implements PriceProvider {

    public static final String ID = "stooq";

    private static final Logger log = LoggerFactory.getLogger(StooqCsvProvider.class);

    /** Our instrument codes mapped to Stooq's ticker symbols. */
    private static final Map<String, String> SYMBOLS = Map.of(
            "XAUUSD", "xauusd",
            "USDVND", "usdvnd");

    private final RestClient client;
    private final AppProperties properties;

    public StooqCsvProvider(RestClient priceFeedClient, AppProperties properties) {
        this.client = priceFeedClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stooq";
    }

    @Override
    public String attribution() {
        return "Dữ liệu lịch sử XAU/USD và USD/VND từ stooq.com";
    }

    @Override
    public boolean enabled() {
        return properties.providers().stooq().enabled();
    }

    @Override
    public List<ProviderQuote> fetch(List<String> instrumentCodes, int maxHistoryDays) {
        List<ProviderQuote> out = new ArrayList<>();
        LocalDate earliest = LocalDate.now().minusDays(maxHistoryDays);

        for (String code : instrumentCodes) {
            String symbol = SYMBOLS.get(code.toUpperCase());
            if (symbol == null) {
                log.warn("Stooq không có symbol tương ứng cho instrument {}", code);
                continue;
            }
            try {
                String csv = client.get()
                        .uri(properties.providers().stooq().baseUrl() + "?s={symbol}&i=d", symbol)
                        .retrieve()
                        .body(String.class);

                List<ProviderQuote> parsed = StooqCsvParser.parse(code, csv, earliest);
                log.info("Stooq {}: nhận {} quan sát", code, parsed.size());
                out.addAll(parsed);
            } catch (Exception ex) {
                log.warn("Không lấy được dữ liệu Stooq cho {}: {}", code, ex.getMessage());
            }
        }
        return out;
    }
}
