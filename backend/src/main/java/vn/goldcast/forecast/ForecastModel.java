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

    /** Machine learning, linear: ridge regression on engineered features, λ tuned on a hold-out. */
    RIDGE("Ridge regression (ML)",
            "Học máy tuyến tính: hồi quy Ridge dự đoán lợi suất ngày kế tiếp từ 12 đặc trưng "
                    + "(lợi suất trễ, trung bình động, độ biến động, khoảng cách SMA20, RSI). "
                    + "Hệ số phạt λ được chọn trên tập validation theo thời gian."),

    /** Machine learning, non-linear: gradient-boosted regression trees with early stopping. */
    GBM("Gradient boosting (ML)",
            "Học máy phi tuyến: tập hợp cây hồi quy tăng cường theo gradient (cùng họ với "
                    + "XGBoost/LightGBM) trên cùng 12 đặc trưng. Số cây được chọn bằng early "
                    + "stopping trên tập validation theo thời gian."),

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

    /** Models that can actually be fitted, i.e. everything except {@link #AUTO}. */
    public static ForecastModel[] fittable() {
        return new ForecastModel[] {NAIVE, DRIFT, SMA, HOLT_DAMPED, AR_DIFF, RIDGE, GBM};
    }
}
