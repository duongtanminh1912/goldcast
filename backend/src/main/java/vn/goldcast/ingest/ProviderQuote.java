package vn.goldcast.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One observation as a provider reported it, before it is matched to an instrument row.
 *
 * @param instrumentCode which series this belongs to, e.g. {@code XAUUSD} or {@code SJC_HCM}
 * @param observedOn     the day the quote applies to
 * @param close          canonical value; for two-way quotes this is the sell side
 * @param buy            dealer buy price, or {@code null}
 * @param sell           dealer sell price, or {@code null}
 */
public record ProviderQuote(
        String instrumentCode,
        LocalDate observedOn,
        BigDecimal close,
        BigDecimal buy,
        BigDecimal sell) {

    public ProviderQuote {
        if (instrumentCode == null || instrumentCode.isBlank()) {
            throw new IllegalArgumentException("instrumentCode không được rỗng");
        }
        if (observedOn == null) {
            throw new IllegalArgumentException("observedOn không được null");
        }
        if (close == null || close.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Giá đóng cửa phải dương cho " + instrumentCode + " ngày " + observedOn);
        }
    }

    public static ProviderQuote single(String code, LocalDate date, BigDecimal close) {
        return new ProviderQuote(code, date, close, null, null);
    }

    public static ProviderQuote twoWay(String code, LocalDate date, BigDecimal buy, BigDecimal sell) {
        return new ProviderQuote(code, date, sell, buy, sell);
    }
}
