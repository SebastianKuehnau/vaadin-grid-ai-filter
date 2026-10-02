package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class RelativeDatesTest {

    @Test
    void computesThePeriodBoundariesAroundAThursdayOnTheFirstOfAMonth() {
        RelativeDates dates = RelativeDates.of(LocalDate.of(2026, 10, 1));

        assertThat(dates).isEqualTo(new RelativeDates(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 27),
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 10, 1)));
    }
}
