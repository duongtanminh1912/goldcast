package vn.goldcast.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vn.goldcast.domain.InstrumentKind;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradingCalendarTest {

    /** 2026-09-18 is a Friday. */
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);

    @Test
    @DisplayName("Vàng thế giới bỏ qua cuối tuần")
    void spotGoldSkipsWeekends() {
        List<LocalDate> dates = TradingCalendar.futureDates(FRIDAY, 3, InstrumentKind.SPOT_GOLD);
        assertEquals(List.of(
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 22),
                LocalDate.of(2026, 9, 23)), dates);
    }

    @Test
    @DisplayName("Tỷ giá cũng chỉ chạy ngày làm việc")
    void fxSkipsWeekends() {
        List<LocalDate> dates = TradingCalendar.futureDates(FRIDAY, 1, InstrumentKind.FX);
        assertEquals(LocalDate.of(2026, 9, 21), dates.get(0));
    }

    @Test
    @DisplayName("Vàng trong nước niêm yết cả cuối tuần")
    void vnGoldIncludesWeekends() {
        List<LocalDate> dates = TradingCalendar.futureDates(FRIDAY, 3, InstrumentKind.VN_GOLD);
        assertEquals(List.of(
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 9, 21)), dates);
    }

    @Test
    @DisplayName("Trả về đúng số ngày yêu cầu và luôn tăng dần")
    void producesRequestedCountInOrder() {
        List<LocalDate> dates = TradingCalendar.futureDates(FRIDAY, 30, InstrumentKind.SPOT_GOLD);
        assertEquals(30, dates.size());
        for (int i = 1; i < dates.size(); i++) {
            assertTrue(dates.get(i).isAfter(dates.get(i - 1)));
        }
    }

    @Test
    @DisplayName("Chuỗi ngày làm việc không chứa thứ Bảy hay Chủ nhật")
    void businessDaySeriesHasNoWeekends() {
        for (LocalDate date : TradingCalendar.futureDates(FRIDAY, 40, InstrumentKind.SPOT_GOLD)) {
            assertFalse(TradingCalendar.isWeekend(date), date + " rơi vào cuối tuần");
        }
    }

    @Test
    @DisplayName("Tham số không hợp lệ bị từ chối")
    void rejectsInvalidArguments() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> TradingCalendar.futureDates(null, 3, InstrumentKind.SPOT_GOLD)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> TradingCalendar.futureDates(FRIDAY, 0, InstrumentKind.SPOT_GOLD)));
    }

    @Test
    @DisplayName("InstrumentKind biết chuỗi nào chỉ chạy ngày làm việc")
    void kindKnowsItsCalendar() {
        assertAll(
                () -> assertTrue(InstrumentKind.SPOT_GOLD.businessDaysOnly()),
                () -> assertTrue(InstrumentKind.FX.businessDaysOnly()),
                () -> assertFalse(InstrumentKind.VN_GOLD.businessDaysOnly()));
    }
}
