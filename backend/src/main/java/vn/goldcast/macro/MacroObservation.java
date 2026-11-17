package vn.goldcast.macro;

import java.time.LocalDate;

/** One daily value of a macro series. Unlike a price, the value may be zero or negative. */
public record MacroObservation(LocalDate observedOn, double value) {

    public MacroObservation {
        if (observedOn == null) {
            throw new IllegalArgumentException("observedOn không được null");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Giá trị vĩ mô phải hữu hạn, nhận " + value);
        }
    }
}
