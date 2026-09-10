package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.time.LocalDate;

/** Variant 02(b)'s filter values: one {@link FieldCriterion} per field, all AND-combined. */
public record CustomerCriteria(
        FieldCriterion<String> city,
        FieldCriterion<LocalDate> lastOrderDate,
        FieldCriterion<CreditRating> creditRating) {
}
