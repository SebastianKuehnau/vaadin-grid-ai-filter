package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Variant 02(a)'s filter values: exactly one value per field, all AND-combined. */
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
