package br.com.habittracker.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.YearMonth;
import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    private LocalDate day(String date) { return LocalDate.parse(date); }

    @Test void februaryIncludesLeapDayAndExcludesFutureDays() {
        YearMonth month = YearMonth.of(2024, 2);
        assertEquals(29, Rules.eligibleDays(month.atDay(1), month.atEndOfMonth(), day("2024-01-01"), null, day("2024-03-01")));
        assertEquals(10, Rules.eligibleDays(month.atDay(1), month.atEndOfMonth(), day("2024-01-01"), null, day("2024-02-10")));
    }

    @Test void periodIsInclusiveAndFutureMonthsHaveNoDenominator() {
        assertEquals(3, Rules.eligibleDays(day("2026-10-01"), day("2026-10-31"), day("2026-10-10"), day("2026-10-12"), day("2026-10-15")));
        assertEquals(0, Rules.eligibleDays(day("2026-11-01"), day("2026-11-30"), day("2026-10-01"), null, day("2026-10-15")));
    }

    @Test void entriesRespectPeriodAndToday() {
        LocalDate start = day("2026-10-01"), end = day("2026-10-10"), today = day("2026-10-15");
        assertDoesNotThrow(() -> Rules.entryDate(start, start, end, today));
        assertDoesNotThrow(() -> Rules.entryDate(end, start, end, today));
        assertThrows(IllegalArgumentException.class, () -> Rules.entryDate(start.minusDays(1), start, end, today));
        assertThrows(IllegalArgumentException.class, () -> Rules.entryDate(end.plusDays(1), start, end, today));
        assertThrows(IllegalArgumentException.class, () -> Rules.entryDate(today.plusDays(1), start, null, today));
    }
}
