package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Variant 02(a)'s filter values: exactly <em>one</em> value per field, extracted from a
 * natural-language query via tool calling. Every field is optional ({@code null} means "don't filter
 * on this"); all given fields are combined with AND by {@link CustomerSpecifications#from}.
 * <p>
 * This is the least expressive filter type in the tutorial's escalation ladder — deliberately so.
 * There is no {@code List} anywhere (so no OR within a field), no operator (each field's comparison
 * is hard-wired in {@link CustomerSpecifications}) and no negation. Variant 02(b)
 * ({@code ai/operator}) adds an operator and a negate flag per field, and
 * {@code 03-ai-structured-filter} / {@code 04-ai-hybrid-filter} replace the per-field shape with a
 * condition list.
 */
public record CustomerCriteria(
        String companyName,
        String contactName,
        String email,
        String phone,
        LocalDate customerSince,
        LocalDate lastOrderDate,
        String country,
        String city,
        String postalCode,
        String street,
        String houseNumber,
        CreditRating creditRating,
        BigDecimal annualRevenue) {

    /** True if no field is set at all, i.e. this filters nothing and matches every customer. */
    public boolean isEmpty() {
        return companyName == null && contactName == null && email == null && phone == null
                && customerSince == null && lastOrderDate == null && country == null && city == null
                && postalCode == null && street == null && houseNumber == null
                && creditRating == null && annualRevenue == null;
    }
}
