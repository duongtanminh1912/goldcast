package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vn.goldcast.config.AppProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * Vietnamese bullion quotes from SJC's public XML rate feed.
 *
 * <p>The feed gives today's two-way prices only — there is no history endpoint — so the
 * domestic series builds up one day at a time from the moment this application starts
 * running. That is a real constraint of the source, not something to paper over: until
 * enough days accumulate, a domestic forecast has little to work with, and the API says so
 * rather than quietly producing one anyway.
 *
 * <p>All parsing lives in {@link SjcXmlParser}; this class only fetches.
 */
@Component
public class SjcGoldProvider implements PriceProvider {

    public static final String ID = "sjc";

    private static final Logger log = LoggerFactory.getLogger(SjcGoldProvider.class);

    private final RestClient client;
    private final AppProperties properties;

    public SjcGoldProvider(RestClient priceFeedClient, AppProperties properties) {
        this.client = priceFeedClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "SJC";
    }

    @Override
    public String attribution() {
        return "Giá vàng miếng công bố bởi Công ty Vàng bạc Đá quý Sài Gòn (SJC)";
    }

    @Override
    public boolean enabled() {
        return properties.providers().sjc().enabled();
    }

    @Override
    public List<ProviderQuote> fetch(List<String> instrumentCodes, int maxHistoryDays) {
        try {
            String xml = client.get()
                    .uri(properties.providers().sjc().url())
                    .retrieve()
                    .body(String.class);

            List<ProviderQuote> quotes = SjcXmlParser.parse(xml, LocalDate.now(), instrumentCodes);
            if (quotes.isEmpty()) {
                log.warn("SJC: không khớp được dòng giá nào cho {}. "
                        + "Có thể cấu trúc feed đã thay đổi — kiểm tra app.providers.sjc.url",
                        instrumentCodes);
            } else {
                log.info("SJC: nhận {} quan sát cho {}", quotes.size(), instrumentCodes);
            }
            return quotes;
        } catch (Exception ex) {
            // A feed being unreachable is normal operational weather, not a failure to raise.
            log.warn("Không lấy được dữ liệu SJC: {}", ex.getMessage());
            return List.of();
        }
    }
}
