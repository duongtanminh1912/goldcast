package vn.goldcast.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.goldcast.api.dto.InstrumentDto;
import vn.goldcast.api.dto.MarketSummaryDto;
import vn.goldcast.api.dto.SeriesDto;
import vn.goldcast.market.MarketService;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Market", description = "Giá hiện tại, lịch sử và quy đổi giữa vàng thế giới và trong nước")
public class MarketController {

    private final MarketService market;

    public MarketController(MarketService market) {
        this.market = market;
    }

    @GetMapping("/instruments")
    @Operation(summary = "Danh sách các chuỗi giá đang theo dõi")
    public List<InstrumentDto> instruments() {
        return market.instruments();
    }

    @GetMapping("/market/summary")
    @Operation(summary = "Tổng quan thị trường",
            description = "Giá vàng thế giới, tỷ giá USD/VND, giá các thương hiệu trong nước "
                    + "và mức chênh lệch so với giá thế giới quy đổi.")
    public ResponseEntity<MarketSummaryDto> summary() {
        // Quotes refresh at most hourly; a short cache spares the database on a busy dashboard.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic())
                .body(market.summary());
    }

    @GetMapping("/prices/{code}")
    @Operation(summary = "Lịch sử giá của một chuỗi",
            description = "Truyền from/to để lấy theo khoảng ngày, hoặc limit để lấy N quan sát gần nhất.")
    public ResponseEntity<SeriesDto> prices(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Min(1) @Max(5000) Integer limit) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(2)).cachePublic())
                .body(market.series(code, from, to, limit));
    }
}
