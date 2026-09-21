package vn.goldcast.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.goldcast.config.AppProperties;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives {@link IngestService} on a schedule and once at startup.
 *
 * <p>A guard flag keeps overlapping passes out: the cron fires hourly but a cold backfill
 * over years of history can take longer than that, and two concurrent passes would fight
 * over the same unique constraint.
 */
@Component
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestService ingestService;
    private final AppProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

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
        if (!running.compareAndSet(false, true)) {
            log.info("Bỏ qua ingest ({}): một lượt khác đang chạy", reason);
            return;
        }
        try {
            log.info("Bắt đầu ingest ({})", reason);
            ingestService.run();
        } catch (RuntimeException ex) {
            // Never let a scheduled task die from an upstream problem.
            log.error("Ingest thất bại ({}): {}", reason, ex.getMessage(), ex);
        } finally {
            running.set(false);
        }
    }
}
