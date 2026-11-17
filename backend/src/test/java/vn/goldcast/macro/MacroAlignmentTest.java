package vn.goldcast.macro;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MacroAlignmentTest {

    @Test
    void usesLatestValueOnOrBeforeEachDateAndNeverALaterOne() {
        TreeMap<LocalDate, Double> macro = new TreeMap<>();
        macro.put(LocalDate.of(2026, 1, 5), 1.0);
        macro.put(LocalDate.of(2026, 1, 7), 2.0);
        macro.put(LocalDate.of(2026, 1, 9), 3.0);

        double[] aligned = MacroAlignment.asOf(List.of(
                LocalDate.of(2026, 1, 2),   // before any macro value
                LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 6),   // macro holiday: carry 1.0 forward
                LocalDate.of(2026, 1, 8),   // 1/9 exists but is in the future
                LocalDate.of(2026, 1, 12)), macro);

        assertArrayEquals(new double[] {Double.NaN, 1.0, 1.0, 2.0, 3.0}, aligned);
    }
}
