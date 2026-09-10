package dev.demo.vaadin.aigridfilter.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** What the search form asks for; every field is optional - null (or an empty set) means "don't constrain". */
public record CustomerFilter(
        String companyName,
        String contactName,
        String email,
        String city,
        Set<String> countries,
        Boolean active,
        Set<CreditRating> creditRatings,
        BigDecimal minRevenue,
        BigDecimal maxRevenue,
        LocalDate customerSinceFrom,
        LocalDate customerSinceTo,
        LocalDate lastOrderFrom,
        LocalDate lastOrderTo) {

    /** An empty filter that matches every customer. */
    public static CustomerFilter empty() {
        return new CustomerFilter(null, null, null, null, Set.of(), null, Set.of(),
                null, null, null, null, null, null);
    }
}
