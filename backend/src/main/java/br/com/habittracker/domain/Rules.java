package br.com.habittracker.domain;

import java.time.LocalDate;

public final class Rules {
    private Rules() {}

    public static void entryDate(LocalDate date, LocalDate start, LocalDate end, LocalDate today) {
        if (date.isAfter(today)) throw new IllegalArgumentException("Não é permitido registrar dias futuros.");
        if (date.isBefore(start) || (end != null && date.isAfter(end))) {
            throw new IllegalArgumentException("A data está fora do período do hábito.");
        }
    }

    public static int eligibleDays(LocalDate monthStart, LocalDate monthEnd, LocalDate start,
                                   LocalDate end, LocalDate today) {
        LocalDate first = monthStart.isAfter(start) ? monthStart : start;
        LocalDate last = monthEnd.isBefore(today) ? monthEnd : today;
        if (end != null && end.isBefore(last)) last = end;
        return last.isBefore(first) ? 0 : (int) java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1;
    }
}
