package vn.goldcast.market.live;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.live-gold")
public record LiveGoldProperties(
        boolean enabled,
        @NotBlank String url,
        @Min(1) @Max(3600) int pollSeconds,
        @Min(1) @Max(30) int timeoutSeconds,
        @Min(60) int staleSeconds,
        @Min(1) @Max(365) int retentionDays) {}
