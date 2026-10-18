package vn.goldcast.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.goldcast.ingest.IngestReport;
import vn.goldcast.ingest.IngestService;
import vn.goldcast.ingest.PriceProvider;

import java.util.List;

/**
 * Operational endpoints.
 *
 * <p>AdminTokenFilter requires X-Admin-Token for every endpoint under /api/v1/admin.
 * An empty app.admin.token disables access. Ingest reaches third-party feeds and
 * writes to the database, so callers need the token configured on the backend.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Vận hành: chạy ingest thủ công, xem trạng thái nguồn dữ liệu")
public class AdminController {

    private final IngestService ingestService;
    private final List<PriceProvider> priceProviders;

    public AdminController(IngestService ingestService, List<PriceProvider> priceProviders) {
        this.ingestService = ingestService;
        this.priceProviders = priceProviders;
    }

    @PostMapping("/ingest")
    @Operation(summary = "Chạy một lượt thu thập dữ liệu ngay lập tức")
    public IngestReport ingest() {
        return ingestService.run();
    }

    @GetMapping("/providers")
    @Operation(summary = "Các nguồn dữ liệu đã đăng ký và trạng thái bật/tắt")
    public List<ProviderInfo> providers() {
        return priceProviders.stream()
                .map(provider -> new ProviderInfo(
                        provider.id(),
                        provider.displayName(),
                        provider.attribution(),
                        provider.enabled()))
                .toList();
    }

    public record ProviderInfo(String id, String name, String attribution, boolean enabled) {}
}
