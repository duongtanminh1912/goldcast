package vn.goldcast.macro;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param enabled     fetch macro series from FRED during ingest
 * @param baseUrl     FRED graph CSV endpoint
 * @param historyDays how far back to request
 * @param maxLagDays  the newest macro value may trail the last gold close by at most this
 *                    many calendar days; otherwise ARX_MACRO is not offered for that forecast
 */
@Validated
@ConfigurationProperties(prefix = "app.macro")
public record MacroProperties(
        boolean enabled,
        @NotBlank String baseUrl,
        @Min(30) int historyDays,
        @Min(1) @Max(30) int maxLagDays) {}
