package vn.goldcast.macro;

/**
 * Macro drivers of the gold price, each published daily on business days by FRED.
 *
 * <p>Both are textbook drivers: gold pays no coupon, so a higher real yield raises the
 * cost of holding it; and gold is priced in dollars, so a stronger dollar tends to push
 * the dollar price down. Whether either helps a <em>daily</em> forecast is an empirical
 * question that the backtest answers, not something assumed here.
 */
public enum MacroSeries {

    /** 10-year US Treasury inflation-indexed (TIPS) yield, in percent. Can be negative. */
    REAL_YIELD_10Y("DFII10", "realYield10y", "Lợi suất thực 10 năm Mỹ (TIPS)", "%"),

    /** Nominal broad US dollar index published by the Federal Reserve. */
    USD_BROAD("DTWEXBGS", "usdBroad", "Chỉ số USD rộng của Fed", "điểm");

    private final String fredId;
    private final String paramName;
    private final String label;
    private final String unit;

    MacroSeries(String fredId, String paramName, String label, String unit) {
        this.fredId = fredId;
        this.paramName = paramName;
        this.label = label;
        this.unit = unit;
    }

    /** Series identifier on FRED, used in the download URL and the CSV header. */
    public String fredId() {
        return fredId;
    }

    /** Short camelCase name used for model parameter keys, e.g. {@code realYield10y_lag1}. */
    public String paramName() {
        return paramName;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }
}
