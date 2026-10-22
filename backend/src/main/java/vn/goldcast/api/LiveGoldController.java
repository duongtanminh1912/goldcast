package vn.goldcast.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.goldcast.api.dto.LiveGoldDto;
import vn.goldcast.market.live.LiveGoldService;

@RestController
@Validated
public class LiveGoldController {
    private final LiveGoldService service;

    public LiveGoldController(LiveGoldService service) { this.service = service; }

    @GetMapping("/api/v1/market/world/live")
    @Operation(summary = "Giá XAU/USD theo phút và các quan sát gần nhất",
            description = "Giá USD/troy ounce, timestamp của nguồn, trạng thái độ mới; tách khỏi chuỗi ngày dùng dự báo.")
    public ResponseEntity<LiveGoldDto> live(@RequestParam(defaultValue = "120") @Min(1) @Max(1440) int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot(limit));
    }
}
