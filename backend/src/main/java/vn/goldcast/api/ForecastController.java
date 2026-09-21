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
import vn.goldcast.api.dto.BacktestDto;
import vn.goldcast.api.dto.ForecastDto;
import vn.goldcast.forecast.ForecastModel;
import vn.goldcast.forecast.ForecastService;

import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Validated
@Tag(name = "Forecast", description = "Dự báo thống kê và đánh giá độ chính xác bằng backtest")
public class ForecastController {

    private final ForecastService forecasts;

    public ForecastController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping("/forecast/{code}")
    @Operation(summary = "Dự báo giá cho một chuỗi",
            description = "Mặc định model=AUTO: backtest mọi mô hình rồi chọn mô hình có MASE thấp "
                    + "nhất. Khoảng tin cậy được suy ra từ sai số out-of-sample thực đo.")
    public ForecastDto forecast(
            @PathVariable String code,
            @RequestParam(required = false) ForecastModel model,
            @RequestParam(required = false) @Min(1) @Max(365) Integer horizon,
            @RequestParam(defaultValue = "false") boolean persist) {

        return forecasts.forecast(code, model, horizon, persist);
    }

    @GetMapping("/backtest/{code}")
    @Operation(summary = "So sánh độ chính xác của mọi mô hình trên chuỗi này",
            description = "Đánh giá rolling-origin: mô hình được khớp lại tại từng điểm gốc "
                    + "chỉ bằng dữ liệu có trước đó.")
    public BacktestDto backtest(
            @PathVariable String code,
            @RequestParam(required = false) @Min(1) @Max(365) Integer horizon) {

        return forecasts.backtest(code, horizon);
    }

    @GetMapping("/models")
    @Operation(summary = "Danh mục mô hình dự báo và mô tả cách hoạt động")
    public List<ModelInfo> models() {
        return Arrays.stream(ForecastModel.values())
                .map(model -> new ModelInfo(model.name(), model.displayName(), model.description(),
                        model != ForecastModel.AUTO))
                .toList();
    }

    /**
     * @param fittable false for AUTO, which selects among the others rather than being one
     */
    public record ModelInfo(String id, String label, String description, boolean fittable) {}
}
