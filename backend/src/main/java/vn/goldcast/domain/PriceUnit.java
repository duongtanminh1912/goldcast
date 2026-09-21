package vn.goldcast.domain;

/** Unit a price is quoted in, with the label and rounding the UI should use. */
public enum PriceUnit {

    USD_PER_TROY_OUNCE("USD/oz", 2),
    VND_PER_TAEL("VNĐ/lượng", 0),
    VND_PER_USD("VNĐ/USD", 0);

    private final String label;
    private final int displayScale;

    PriceUnit(String label, int displayScale) {
        this.label = label;
        this.displayScale = displayScale;
    }

    public String label() {
        return label;
    }

    /** Number of decimal places worth showing; VND amounts are never shown with cents. */
    public int displayScale() {
        return displayScale;
    }
}
