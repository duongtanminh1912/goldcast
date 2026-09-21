package vn.goldcast.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * A single {@link RestClient} for outbound price feeds, with timeouts set explicitly.
 *
 * <p>Without them an unresponsive upstream would hold an ingest thread indefinitely and
 * the scheduler would pile runs on top of each other.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public RestClient priceFeedClient(AppProperties properties) {
        Duration timeout = Duration.ofSeconds(properties.ingest().timeoutSeconds());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "goldcast/0.1 (+https://github.com/; price ingest)")
                .defaultHeader("Accept", "text/csv,application/xml,text/html,*/*")
                .build();
    }
}
