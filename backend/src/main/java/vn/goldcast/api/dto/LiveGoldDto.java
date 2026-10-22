package vn.goldcast.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record LiveGoldDto(
        String symbol, String currency, String unit, String source, String sourceUrl,
        boolean enabled, int pollIntervalSeconds, int staleAfterSeconds, boolean referenceSession,
        String status, OffsetDateTime generatedAt, OffsetDateTime lastAttemptAt,
        OffsetDateTime lastFetchAt, boolean lastFetchSucceeded,
        LiveGoldPoint latest, List<LiveGoldPoint> points) {

    public record LiveGoldPoint(BigDecimal usdPerOunce, OffsetDateTime observedAt, OffsetDateTime receivedAt) {}
}
