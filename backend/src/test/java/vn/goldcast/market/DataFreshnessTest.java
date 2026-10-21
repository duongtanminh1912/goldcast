package vn.goldcast.market;

import org.junit.jupiter.api.Test;
import vn.goldcast.domain.InstrumentKind;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class DataFreshnessTest {
    private final LocalDate friday = LocalDate.of(2026, 10, 16);

    @Test void spotDoesNotCountWeekendAsMissingPublications() {
        assertNull(DataFreshness.warning("XAUUSD", InstrumentKind.SPOT_GOLD, friday, friday.plusDays(4)));
        assertNotNull(DataFreshness.warning("XAUUSD", InstrumentKind.SPOT_GOLD, friday, friday.plusDays(5)));
    }
    @Test void domesticGoldCountsWeekend() {
        assertNotNull(DataFreshness.warning("SJC", InstrumentKind.VN_GOLD, friday, friday.plusDays(3)));
    }
    @Test void emptyAndFutureDataCannotLookFresh() {
        assertNotNull(DataFreshness.warning("USDVND", InstrumentKind.FX, null, friday));
        assertNotNull(DataFreshness.warning("USDVND", InstrumentKind.FX, friday.plusDays(1), friday));
        assertNull(DataFreshness.warning("USDVND", InstrumentKind.FX, friday, friday));
    }
    @Test void oldSeriesCannotEscapeAgeWarningAfterWholeWeeks() {
        assertTrue(DataFreshness.warning("USDVND", InstrumentKind.FX, friday.minusWeeks(3), friday).contains("15"));
    }
}
