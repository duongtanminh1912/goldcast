package vn.goldcast.macro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import vn.goldcast.ingest.IngestReport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the macro drivers from FRED and stores them.
 *
 * <p>Runs after the price ingest has committed, in its own transactions, so a macro
 * failure can never roll back price data. Network calls happen outside any transaction.
 */
@Service
public class MacroIngest {

    public static final String PROVIDER_ID = "fred";

    private static final Logger log = LoggerFactory.getLogger(MacroIngest.class);

    private final RestClient client;
    private final MacroStore store;
    private final MacroProperties properties;
    private final SyntheticMacroGenerator seedGenerator;
    private final TransactionTemplate transactions;

    public MacroIngest(RestClient priceFeedClient, MacroStore store, MacroProperties properties,
                       SyntheticMacroGenerator seedGenerator, PlatformTransactionManager transactionManager) {
        this.client = priceFeedClient;
        this.store = store;
        this.properties = properties;
        this.seedGenerator = seedGenerator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public IngestReport.ProviderOutcome refresh(boolean seedOnEmpty) {
        LocalDate today = LocalDate.now();
        LocalDate earliest = today.minusDays(properties.historyDays());
        int quotes = 0;
        int inserted = 0;
        int updated = 0;
        List<String> problems = new ArrayList<>();

        if (properties.enabled()) {
            for (MacroSeries series : MacroSeries.values()) {
                try {
                    String csv = client.get()
                            .uri(properties.baseUrl() + "?id={id}&cosd={from}", series.fredId(), earliest)
                            .retrieve()
                            .body(String.class);
                    List<MacroObservation> parsed = FredCsvParser.parse(series.fredId(), csv, earliest);
                    if (parsed.isEmpty()) {
                        problems.add(series.fredId() + ": không nhận được dữ liệu");
                        continue;
                    }
                    quotes += parsed.size();
                    int[] written = transactions.execute(status -> store.upsert(series, parsed, PROVIDER_ID));
                    inserted += written[0];
                    updated += written[1];
                    log.info("FRED {}: nhận {} quan sát, +{} mới, {} cập nhật",
                            series.fredId(), parsed.size(), written[0], written[1]);
                } catch (RuntimeException ex) {
                    log.warn("Không lấy được FRED {}: {}", series.fredId(), ex.getMessage());
                    problems.add(series.fredId() + ": " + ex.getMessage());
                }
            }
        }

        List<String> seeded = new ArrayList<>();
        if (seedOnEmpty) {
            for (MacroSeries series : MacroSeries.values()) {
                try {
                    Integer added = transactions.execute(status -> store.count(series) > 0 ? 0
                            : store.upsert(series, seedGenerator.generate(series, today, properties.historyDays()),
                                    SyntheticMacroGenerator.SOURCE)[0]);
                    if (added != null && added > 0) {
                        inserted += added;
                        seeded.add(series.fredId());
                    }
                } catch (RuntimeException ex) {
                    problems.add(series.fredId() + " (mô phỏng): " + ex.getMessage());
                }
            }
            if (!seeded.isEmpty()) {
                log.warn("Chuỗi vĩ mô {} chưa có dữ liệu thật, đã sinh dữ liệu mô phỏng", seeded);
            }
        }

        String message;
        if (!properties.enabled()) {
            message = "Provider đang tắt trong cấu hình";
        } else if (problems.isEmpty()) {
            message = "OK";
        } else {
            message = String.join("; ", problems);
        }
        if (!seeded.isEmpty()) {
            message += "; sinh dữ liệu mô phỏng cho " + String.join(", ", seeded);
        }
        return new IngestReport.ProviderOutcome(PROVIDER_ID, properties.enabled(), quotes, inserted, updated, message);
    }
}
