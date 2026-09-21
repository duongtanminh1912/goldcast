package vn.goldcast.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.goldcast.analytics.IndicatorService;
import vn.goldcast.api.dto.IndicatorsDto;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Indicators", description = "Chỉ báo kỹ thuật: SMA, EMA, RSI, Bollinger Bands")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @GetMapping("/indicators/{code}")
    @Operation(summary = "Chỉ báo kỹ thuật của một chuỗi giá",
            description = "Các mảng trả về song song với mảng dates, giá trị null ở những vị trí "
                    + "chỉ báo chưa xác định.")
    public IndicatorsDto indicators(
            @PathVariable String code,
            @RequestParam(defaultValue = "180") @Min(50) @Max(2000) int lookback) {

        return indicatorService.compute(code, lookback);
    }
}
