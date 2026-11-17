package vn.goldcast.forecast;

/**
 * Catalogue of forecasting models exposed by the API.
 *
 * <p>{@link #AUTO} is not a model in itself: the service backtests every candidate
 * on the instrument's own history and picks the one with the lowest MASE.
 */
public enum ForecastModel {

    /** Last observed value carried forward. The benchmark every other model must beat. */
    NAIVE("Naive (random walk)", "Giá dự báo bằng đúng giá đóng cửa gần nhất."),

    /** Random walk with drift: extrapolates the average change over the training window. */
    DRIFT("Drift", "Ngoại suy theo mức thay đổi trung bình của toàn bộ chuỗi lịch sử."),

    /** Flat forecast at the mean of the last k observations. */
    SMA("Simple moving average", "Dự báo phẳng bằng trung bình k phiên gần nhất."),

    /** Holt's linear trend with a damping parameter, so the trend flattens out. */
    HOLT_DAMPED("Holt damped trend", "San mũ có xu hướng bị tắt dần theo hệ số phi."),

    /** Autoregression on first differences — an ARIMA(p,1,0) fitted by ordinary least squares. */
    AR_DIFF("AR(p) on differences", "Hồi quy tự tương quan trên sai phân bậc 1, tương đương ARIMA(p,1,0)."),

    /**
     * AR(p) on differences plus lagged changes of macro drivers (US real yield, US dollar
     * index). Only offered for world spot gold, and only when macro data is present.
     */
    ARX_MACRO("ARX macro (real yield + USD)",
            "AR(p) trên sai phân cộng biến động trễ một ngày của lợi suất thực 10 năm Mỹ và "
                    + "chỉ số USD. Tương lai của hai biến này được giả định đứng yên."),

    /** Pick whichever of the above scores best in a rolling-origin backtest. */
    AUTO("Auto (chọn theo backtest)", "Chạy backtest mọi mô hình và chọn mô hình có MASE thấp nhất.");

    private final String displayName;
    private final String description;

    ForecastModel(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /**
     * Univariate models that can be fitted from the price series alone. {@link #ARX_MACRO}
     * is excluded because it also needs aligned macro data; the service adds it separately.
     */
    public static ForecastModel[] fittable() {
        return new ForecastModel[] {NAIVE, DRIFT, SMA, HOLT_DAMPED, AR_DIFF};
    }
}
