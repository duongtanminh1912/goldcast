package vn.goldcast.market.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class GoldApiClientTest {
    private GoldApiClient client(String url) {
        return new GoldApiClient(new LiveGoldProperties(true, url, 60, 1, 180, 30), new ObjectMapper());
    }

    private String json(String symbol, String currency, String price, String timestamp) {
        return "{\"symbol\":\"" + symbol + "\",\"currency\":\"" + currency
                + "\",\"price\":" + price + ",\"updatedAt\":\"" + timestamp + "\"}";
    }

    @Test
    void parsesUsdSpotAndPreservesSourceTimestampAndPrecision() throws Exception {
        var quote = client("https://example.test").parse(json("XAU", "USD", "4099.299805", "2026-10-22T06:17:40Z"));
        assertEquals("4099.299805", quote.usdPerOunce().toPlainString());
        assertEquals(Instant.parse("2026-10-22T06:17:40Z"), quote.observedAt());
    }

    @Test
    void refusesWrongAssetCurrencyNonpositivePriceAndMissingTimestamp() {
        var client = client("https://example.test");
        for (String body : new String[]{json("XAG", "USD", "4000", "2026-10-22T06:17:40Z"),
                json("XAU", "EUR", "4000", "2026-10-22T06:17:40Z"),
                json("XAU", "USD", "0", "2026-10-22T06:17:40Z"),
                json("XAU", "USD", "-1", "2026-10-22T06:17:40Z"),
                json("XAU", "USD", "\"4000\"", "2026-10-22T06:17:40Z"),
                json("XAU", "USD", "4000", "not-a-timestamp"), "{}", "null", "<html>error</html>"}) {
            assertThrows(IOException.class, () -> client.parse(body));
        }
    }

    @Test
    void nonSuccessStatusIsNotAcceptedAsAQuote() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(429, -1); exchange.close(); });
        server.start();
        try {
            assertThrows(IOException.class, () -> client("http://127.0.0.1:" + server.getAddress().getPort()).fetch());
        } finally { server.stop(0); }
    }

    @Test
    void oversizedBodyIsRejectedWhileStreaming() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 20_000);
            try { exchange.getResponseBody().write(new byte[20_000]); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            assertThrows(IOException.class, () -> client("http://127.0.0.1:" + server.getAddress().getPort()).fetch());
        } finally { server.stop(0); }
    }

    @Test
    void deadlineCoversSlowResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            byte[] body = json("XAU", "USD", "4000", "2026-10-22T06:17:40Z").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try {
                Thread.sleep(3000);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThrows(IOException.class, () -> client("http://127.0.0.1:" + server.getAddress().getPort()).fetch()));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
