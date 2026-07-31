package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Variant 02(b)'s filter values: per field one {@link FieldCriterion} — one value, one
 * {@link Operator}, one {@code negate} flag. Every field is optional ({@code null} means "don't
 * filter on this"); all given fields are combined with AND by {@link OperatorSpecifications#from}.
 * <p>
 * Compared with variant 02(a)'s {@code CustomerCriteria} this triples the tool-parameter count (13
 * fields × 3 parameters = 39) and buys exactly two capabilities: negation and operator precision
 * (EQUALS / STARTS_WITH / ENDS_WITH, day-level date bounds). What it still cannot express, by
 * construction, is multi-value OR within a field and any value range — see {@link FieldCriterion}.
 * That is the point of this rung of the ladder: a lot more plumbing, still not general-purpose
 * expressiveness.
 */
public record OperatorCriteria(
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
