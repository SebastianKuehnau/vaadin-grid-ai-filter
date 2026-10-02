package dev.demo.vaadin.aigridfilter.ai;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/** Today and the period boundaries around it, computed in code so the model never does calendar arithmetic. */
public record RelativeDates(
        LocalDate today,
        LocalDate yesterday,
        LocalDate startOfThisWeek,
        LocalDate startOfLastWeek,
        LocalDate endOfLastWeek,
        LocalDate startOfThisMonth,
        LocalDate startOfLastMonth,
        LocalDate endOfLastMonth,
        LocalDate startOfThisYear,
        LocalDate startOfLastYear,
        LocalDate endOfLastYear,
        LocalDate twelveMonthsAgo) {

    /** Weeks start on Monday. */
    public static RelativeDates of(LocalDate today) {
        LocalDate startOfThisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate startOfThisMonth = today.withDayOfMonth(1);
        LocalDate startOfThisYear = today.withDayOfYear(1);
        return new RelativeDates(
                today,
                today.minusDays(1),
                startOfThisWeek,
                startOfThisWeek.minusWeeks(1),
                startOfThisWeek.minusDays(1),
                startOfThisMonth,
                startOfThisMonth.minusMonths(1),
                startOfThisMonth.minusDays(1),
                startOfThisYear,
                startOfThisYear.minusYears(1),
                startOfThisYear.minusDays(1),
                today.minusMonths(12));
    }
}
