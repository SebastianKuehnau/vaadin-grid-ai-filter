package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.operator.FieldCriterion.Operator;
import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Variant 02(b)'s filter values: one {@link FieldCriterion} per field, all AND-combined. */
public record CustomerCriteria(
        FieldCriterion<String> companyName,
        FieldCriterion<String> contactName,
        FieldCriterion<String> email,
        FieldCriterion<String> phone,
        FieldCriterion<LocalDate> customerSince,
        FieldCriterion<LocalDate> lastOrderDate,
        FieldCriterion<String> country,
        FieldCriterion<String> city,
        FieldCriterion<String> postalCode,
        FieldCriterion<String> street,
        FieldCriterion<String> houseNumber,
        FieldCriterion<CreditRating> creditRating,
        FieldCriterion<BigDecimal> annualRevenue) {

    /** True if no field is set at all, i.e. this filters nothing and matches every customer. */
    public boolean isEmpty() {
        return companyName == null && contactName == null && email == null && phone == null
                && customerSince == null && lastOrderDate == null && country == null && city == null
                && postalCode == null && street == null && houseNumber == null
                && creditRating == null && annualRevenue == null;
    }
}
