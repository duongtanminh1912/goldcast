package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vn.goldcast.config.AppProperties;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domestic gold quotes from PNJ's public price endpoint, the one behind pnj.com.vn.
 *
 * <p>This replaced SJC's own XML feed, which now returns 404 and whose site sits behind a
 * Cloudflare challenge. PNJ lists SJC bars by branch zone, so the SJC series keep their
 * meaning — they become "SJC bars as quoted by PNJ" in each city. Like SJC's feed, it only
 * carries today's price; history accumulates one observation per day from first run.
 */
@Component
public class PnjGoldProvider implements PriceProvider {

    public static final String ID = "pnj";

    private static final Logger log = LoggerFactory.getLogger(PnjGoldProvider.class);

    /** Our instrument → (PNJ zone, PNJ product code). Zone 00 is Hồ Chí Minh, 11 is Hà Nội. */
    private static final Map<String, Target> TARGETS = Map.of(
            "SJC_HCM", new Target("00", "SJC"),
            "SJC_HN", new Target("11", "SJC"),
            "PNJ_RING", new Target("00", "N24K"));

    private final RestClient client;
    private final AppProperties properties;

    public PnjGoldProvider(RestClient priceFeedClient, AppProperties properties) {
        this.client = priceFeedClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "PNJ";
    }

    @Override
    public String attribution() {
        return "Giá vàng niêm yết theo khu vực trên pnj.com.vn";
    }

    @Override
    public boolean enabled() {
        return properties.providers().pnj().enabled();
    }

    @Override
    public List<ProviderQuote> fetch(List<String> instrumentCodes, int maxHistoryDays) {
        // One request per zone, however many products we read from it.
        Map<String, Map<String, String>> byZone = new LinkedHashMap<>();
        for (String code : instrumentCodes) {
            Target target = TARGETS.get(code.toUpperCase());
            if (target == null) {
                log.warn("PNJ không có sản phẩm tương ứng cho instrument {}", code);
                continue;
            }
            byZone.computeIfAbsent(target.zone(), zone -> new LinkedHashMap<>())
                    .put(code.toUpperCase(), target.product());
        }

        List<ProviderQuote> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> zone : byZone.entrySet()) {
            try {
                String json = client.get()
                        .uri(properties.providers().pnj().url() + "?zone={zone}", zone.getKey())
                        .retrieve()
                        .body(String.class);

                List<ProviderQuote> parsed = PnjGoldParser.parse(json, LocalDate.now(), zone.getValue());
                if (parsed.size() < zone.getValue().size()) {
                    log.warn("PNJ zone {}: chỉ khớp {}/{} sản phẩm {} — kiểm tra lại mã 'masp'",
                            zone.getKey(), parsed.size(), zone.getValue().size(), zone.getValue().values());
                }
                log.info("PNJ zone {}: nhận {} quan sát", zone.getKey(), parsed.size());
                out.addAll(parsed);
            } catch (Exception ex) {
                log.warn("Không lấy được dữ liệu PNJ zone {}: {}", zone.getKey(), ex.getMessage());
            }
        }
        return out;
    }

    private record Target(String zone, String product) {}
}
