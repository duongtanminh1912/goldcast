package vn.goldcast.ingest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What one ingest pass actually did — returned by the admin endpoint and logged, so a
 * feed that has quietly stopped producing rows is visible rather than inferred.
 */
public record IngestReport(
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<ProviderOutcome> providers,
        int inserted,
        int updated,
        int seededInstruments) {

    /**
     * @param provider  provider id
     * @param enabled   whether it was switched on
     * @param quotes    how many observations came back
     * @param inserted  new rows written
     * @param updated   existing rows whose value changed
     * @param message   short status, including the reason when nothing came back
     */
    public record ProviderOutcome(
            String provider,
            boolean enabled,
            int quotes,
            int inserted,
            int updated,
            String message) {}

    public long durationMillis() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }

    public boolean anythingChanged() {
        return inserted > 0 || updated > 0 || seededInstruments > 0;
    }
}
