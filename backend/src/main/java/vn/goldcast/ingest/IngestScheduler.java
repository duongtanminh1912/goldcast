package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.goldcast.config.AppProperties;

/**
 * Drives {@link IngestService} on a schedule and once at startup.
 *
 * <p>IngestService guards the whole transaction against overlapping manual,
 * scheduled and startup calls.
 */
@Component
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestService ingestService;
    private final AppProperties properties;

    public IngestScheduler(IngestService ingestService, AppProperties properties) {
        this.ingestService = ingestService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.ingest().enabled() || !properties.ingest().runOnStartup()) {
            log.info("Bỏ qua ingest lúc khởi động (enabled={}, runOnStartup={})",
                    properties.ingest().enabled(), properties.ingest().runOnStartup());
            return;
        }
        trigger("khởi động");
    }

    @Scheduled(cron = "${app.ingest.cron}")
    public void onSchedule() {
        if (!properties.ingest().enabled()) {
            return;
        }
        trigger("lịch định kỳ");
    }

    private void trigger(String reason) {
        try {
            log.info("Bắt đầu ingest ({})", reason);
            ingestService.run();
        } catch (IngestAlreadyRunningException ex) {
            log.info("Bỏ qua ingest ({}): một lượt khác đang chạy", reason);
        } catch (RuntimeException ex) {
            // Never let a scheduled task die from an upstream problem.
            log.error("Ingest thất bại ({}): {}", reason, ex.getMessage(), ex);
        }
    }
}
