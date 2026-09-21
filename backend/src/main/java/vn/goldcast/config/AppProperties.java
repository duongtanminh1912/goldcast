package vn.goldcast.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Everything tunable about the application, bound from {@code app.*} and validated at
 * startup so a bad value fails the boot rather than surfacing as a strange forecast.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(Cors cors, Ingest ingest, Providers providers, Forecast forecast) {

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                    ? List.of("http://localhost:3000")
                    : List.copyOf(allowedOrigins);
        }
    }

    public record Ingest(
            boolean enabled,
            boolean seedOnEmpty,
            @NotBlank String cron,
            boolean runOnStartup,
            @Min(30) int historyDays,
            @Min(1) int timeoutSeconds) {}

    public record Providers(Stooq stooq, Sjc sjc) {

        public record Stooq(boolean enabled, @NotBlank String baseUrl) {}

        public record Sjc(boolean enabled, @NotBlank String url) {}
    }

    public record Forecast(
            @Min(1) int defaultHorizon,
            @Min(1) int maxHorizon,
            @Min(5) int minHistory,
            @Min(10) int backtestMinTrain,
            @Min(1) int backtestMaxOrigins,
            @Min(50) int maxTrainWindow) {

        public Forecast {
            if (defaultHorizon > maxHorizon) {
                throw new IllegalArgumentException(
                        "app.forecast.default-horizon (" + defaultHorizon
                                + ") không được lớn hơn max-horizon (" + maxHorizon + ")");
            }
        }
    }
}
