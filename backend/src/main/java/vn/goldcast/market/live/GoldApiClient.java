package vn.goldcast.market.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.List;

/** One public upstream request per scheduled poll, never per visitor. */
@Component
public class GoldApiClient {
    private final LiveGoldProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public GoldApiClient(LiveGoldProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Quote fetch() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.url()))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Accept", "application/json").header("User-Agent", "GoldCast/0.1")
                .GET().build();
        var pending = client.sendAsync(request, info -> new LimitedBody());
        HttpResponse<String> response;
        try {
            // The overall deadline includes consumption of the response body.
            response = pending.get(properties.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException ex) {
            pending.cancel(true);
            throw new IOException("Quote request did not complete");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            throw ex;
        }
        if (response.statusCode() != 200) throw new IOException("Upstream HTTP " + response.statusCode());
        if (response.body().length() > 16_384) throw new IOException("Oversized quote response");
        return parse(response.body());
    }

    Quote parse(String json) throws IOException {
        JsonNode data = mapper.readTree(json);
        if (data == null || !"XAU".equals(data.path("symbol").asText())
                || !"USD".equals(data.path("currency").asText()) || !data.path("price").isNumber()) {
            throw new IOException("Unexpected quote contract");
        }
        try {
            BigDecimal price = data.get("price").decimalValue().setScale(6, RoundingMode.HALF_UP);
            if (price.signum() <= 0 || price.precision() > 20) throw new IllegalArgumentException("Invalid price");
            Instant observedAt = Instant.parse(data.path("updatedAt").asText());
            return new Quote(price, observedAt);
        } catch (RuntimeException ex) {
            throw new IOException("Invalid quote value or timestamp");
        }
    }

    public record Quote(BigDecimal usdPerOunce, Instant observedAt) {}

    /** Limit bytes before buffering, including HTML/error pages from an upstream proxy. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private int bytes;
        private boolean done;
        public CompletionStage<String> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            bytes += buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            if (bytes > 16_384) {
                done = true;
                subscription.cancel();
                delegate.onError(new IOException("Oversized quote response"));
            } else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { if (!done) { done = true; delegate.onError(error); } }
        public void onComplete() { if (!done) { done = true; delegate.onComplete(); } }
    }
}
