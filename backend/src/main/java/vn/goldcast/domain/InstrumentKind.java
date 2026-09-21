package vn.goldcast.domain;

/** What a series actually measures — this drives calendars, formatting and conversion. */
public enum InstrumentKind {

    /** World spot gold, quoted in USD per troy ounce, trading on business days. */
    SPOT_GOLD,

    /** A Vietnamese dealer's bullion quote in VND per lượng, published every calendar day. */
    VN_GOLD,

    /** A foreign-exchange rate, business days only. */
    FX;

    /** True when the series only has observations on weekdays. */
    public boolean businessDaysOnly() {
        return this != VN_GOLD;
    }
}
