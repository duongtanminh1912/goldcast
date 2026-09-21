package vn.goldcast.market;

/**
 * Unit conversion between the world gold quote and the Vietnamese one.
 *
 * <p>World spot gold is quoted in USD per troy ounce. Vietnamese dealers quote VND per
 * <em>lượng</em> (also called cây, = 10 chỉ = 37.5 g) for 99.99% bullion. Comparing the
 * two therefore needs both a mass conversion and the USD/VND rate:
 *
 * <pre>
 *   VND/lượng = USD/oz ÷ 31.1034768 g × 37.5 g × USD/VND
 * </pre>
 *
 * <p>The gap between that figure and what SJC actually charges is the domestic premium —
 * in Vietnam it is large and policy-driven, so it is surfaced as a first-class number
 * rather than buried.
 */
public final class GoldUnits {

    /** One troy ounce in grams, by definition. */
    public static final double TROY_OUNCE_IN_GRAMS = 31.1034768;

    /** One lượng (cây) in grams — the Vietnamese bullion unit. */
    public static final double TAEL_IN_GRAMS = 37.5;

    /** One chỉ in grams: a tenth of a lượng. */
    public static final double CHI_IN_GRAMS = 3.75;

    /** Grams per lượng ÷ grams per troy ounce: how many ounces one lượng weighs. */
    public static final double TAEL_IN_TROY_OUNCES = TAEL_IN_GRAMS / TROY_OUNCE_IN_GRAMS;

    private GoldUnits() {}

    /**
     * World spot price expressed the way a Vietnamese buyer sees it.
     *
     * @param usdPerTroyOunce world spot, e.g. 2400.0
     * @param usdVndRate      exchange rate, e.g. 25400.0
     * @return VND per lượng, before tax, fabrication cost or dealer margin
     */
    public static double worldToVndPerTael(double usdPerTroyOunce, double usdVndRate) {
        requirePositive(usdPerTroyOunce, "Giá vàng thế giới");
        requirePositive(usdVndRate, "Tỷ giá USD/VND");
        return usdPerTroyOunce * TAEL_IN_TROY_OUNCES * usdVndRate;
    }

    /** Inverse of {@link #worldToVndPerTael}. */
    public static double vndPerTaelToWorld(double vndPerTael, double usdVndRate) {
        requirePositive(vndPerTael, "Giá vàng trong nước");
        requirePositive(usdVndRate, "Tỷ giá USD/VND");
        return vndPerTael / TAEL_IN_TROY_OUNCES / usdVndRate;
    }

    /** VND per lượng converted to VND per chỉ. */
    public static double taelToChi(double vndPerTael) {
        return vndPerTael / 10.0;
    }

    /** VND per lượng converted to VND per gram. */
    public static double taelToGram(double vndPerTael) {
        return vndPerTael / TAEL_IN_GRAMS;
    }

    /**
     * Domestic premium: how much more (or less) the local price is than the
     * world-equivalent price.
     *
     * @param domesticVndPerTael what the dealer charges
     * @param worldVndPerTael    the converted world price
     */
    public static Premium premium(double domesticVndPerTael, double worldVndPerTael) {
        requirePositive(domesticVndPerTael, "Giá trong nước");
        requirePositive(worldVndPerTael, "Giá thế giới quy đổi");
        double absolute = domesticVndPerTael - worldVndPerTael;
        return new Premium(absolute, absolute / worldVndPerTael * 100.0);
    }

    /**
     * @param amountVnd  premium in VND per lượng; negative means the local price is below world
     * @param percent    the same figure relative to the world-equivalent price
     */
    public record Premium(double amountVnd, double percent) {}

    private static void requirePositive(double value, String label) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " phải là số dương hữu hạn, nhận được " + value);
        }
    }
}
