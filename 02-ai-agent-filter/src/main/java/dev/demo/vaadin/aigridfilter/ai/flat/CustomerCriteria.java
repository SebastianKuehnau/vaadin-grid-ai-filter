package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.time.LocalDate;

/** Variant 02(a)'s filter values: exactly one value per field, all AND-combined. */
public record CustomerCriteria(String city, LocalDate lastOrderDate, CreditRating creditRating) {

    /** True if no field is set at all, i.e. this filters nothing and matches every customer. */
    public boolean isEmpty() {
        return city == null && lastOrderDate == null && creditRating == null;
    }
}
