package vn.goldcast.macro;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FredCsvParserTest {

    @Test
    void parsesValuesAndSkipsMissingDaysInsteadOfReadingZero() {
        String csv = "observation_date,DFII10\r\n2026-11-10,2.78\r\n2026-11-11,\r\n2026-11-12,.\r\n2026-11-13,2.91\r\n";
        List<MacroObservation> parsed = FredCsvParser.parse("DFII10", csv, null);
        assertEquals(List.of(
                new MacroObservation(LocalDate.of(2026, 11, 10), 2.78),
                new MacroObservation(LocalDate.of(2026, 11, 13), 2.91)), parsed);
    }

    @Test
    void keepsNegativeRealYields() {
        List<MacroObservation> parsed = FredCsvParser.parse("DFII10", "DATE,DFII10\n2021-08-04,-1.17\n", null);
        assertEquals(-1.17, parsed.get(0).value());
    }

    @Test
    void rejectsAnotherSeriesAnErrorPageOrHtml() {
        assertTrue(FredCsvParser.parse("DFII10", "observation_date,DTWEXBGS\n2026-11-13,122.65\n", null).isEmpty());
        assertTrue(FredCsvParser.parse("DFII10", "<!DOCTYPE html><html>Error</html>", null).isEmpty());
        assertTrue(FredCsvParser.parse("DFII10", "", null).isEmpty());
        assertTrue(FredCsvParser.parse("DFII10", null, null).isEmpty());
    }

    @Test
    void dropsMalformedRowsAndRowsBeforeEarliest() {
        String csv = "﻿observation_date,DTWEXBGS\n2026-01-02,120.1\nnot-a-date,1\n2026-02-30,1\n"
                + "2026-03-02,abc\n2026-03-03,1,2\n2026-03-04,121.5\n";
        List<MacroObservation> parsed = FredCsvParser.parse("DTWEXBGS", csv, LocalDate.of(2026, 2, 1));
        assertEquals(List.of(new MacroObservation(LocalDate.of(2026, 3, 4), 121.5)), parsed);
    }
}
