package br.com.careermatcher.models;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Whole completed months; a valid experience shorter than a month has duration zero. */
public final class ExperiencePeriod {
    private ExperiencePeriod() {}
    public static int months(LocalDate start, LocalDate end) {
        return Math.toIntExact(Math.max(0, ChronoUnit.MONTHS.between(start, end)));
    }
}
