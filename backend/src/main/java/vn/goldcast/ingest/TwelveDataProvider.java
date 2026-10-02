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
 * Daily history from Twelve Data's REST API: world spot gold and the USD/VND rate.
 *
 * <p>This replaced Stooq, whose CSV endpoint started answering with a JavaScript browser
 * check instead of data. Twelve Data is an official, documented API with a free tier
 * (800 requests a day, 8 a minute); an hourly ingest of two symbols uses 48 of them.
 *
 * <p>The provider stays switched off until an API key is configured, so a fresh clone
 * still boots — it just falls back to synthetic data like before.
 */
@Component
public class TwelveDataProvider implements PriceProvider {

    public static final String ID = "twelvedata";

    private static final Logger log = LoggerFactory.getLogger(TwelveDataProvider.class);

    /** Our instrument codes mapped to Twelve Data's symbols. */
    private static final Map<String, String> SYMBOLS = Map.of(
            "XAUUSD", "XAU/USD",
            "USDVND", "USD/VND");

    /** The API's own cap on bars per request. */
    private static final int MAX_OUTPUT_SIZE = 5000;

    private final RestClient client;
    private final AppProperties properties;

    public TwelveDataProvider(RestClient priceFeedClient, AppProperties properties) {
        this.client = priceFeedClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Twelve Data";
    }

    @Override
    public String attribution() {
        return "Dữ liệu lịch sử XAU/USD và USD/VND từ twelvedata.com";
    }

    @Override
    public boolean enabled() {
        AppProperties.Providers.TwelveData config = properties.providers().twelveData();
        return config.enabled() && config.apiKey() != null && !config.apiKey().isBlank();
    }

    @Override
    public List<ProviderQuote> fetch(List<String> instrumentCodes, int maxHistoryDays) {
        AppProperties.Providers.TwelveData config = properties.providers().twelveData();
        List<ProviderQuote> out = new ArrayList<>();
        LocalDate earliest = LocalDate.now().minusDays(maxHistoryDays);
        // Calendar days overstate trading days, so this always covers the window.
        int outputSize = Math.min(MAX_OUTPUT_SIZE, maxHistoryDays);

        for (String code : instrumentCodes) {
            String symbol = SYMBOLS.get(code.toUpperCase());
            if (symbol == null) {
                log.warn("Twelve Data không có symbol tương ứng cho instrument {}", code);
                continue;
            }
            try {
                String json = client.get()
                        .uri(config.baseUrl() + "/time_series?symbol={symbol}&interval=1day"
                                        + "&outputsize={size}&apikey={key}",
                                symbol, outputSize, config.apiKey())
                        .retrieve()
                        .body(String.class);

                List<ProviderQuote> parsed = TwelveDataParser.parse(code, json, earliest);
                log.info("Twelve Data {}: nhận {} quan sát", code, parsed.size());
                out.addAll(parsed);
            } catch (Exception ex) {
                // I/O errors quote the full request URI, which carries the API key.
                String message = String.valueOf(ex.getMessage()).replace(config.apiKey(), "***");
                log.warn("Không lấy được dữ liệu Twelve Data cho {}: {}", code, message);
            }
        }
        return out;
    }
}
