package dev.demo.vaadin.aigridfilter.canonicalquery;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The slice of a customer the canonical query set actually filters on — and nothing else.
 * <p>
 * Each AI module owns its own {@code Customer} entity (the domain classes are duplicated per module on
 * purpose), so a shared expectation cannot be written against any one of them. This record is the small
 * common projection instead: every module maps its own {@code Customer} onto it in a handful of lines,
 * and {@link CanonicalQuery}'s expected result sets are expressed over this type.
 * <p>
 * Reading it top to bottom tells you exactly which six fields the eight canonical queries exercise.
 *
 * @param id             the customer id, used to compare expected and actual result sets
 * @param city           {@code address.city} — the field C1, C2, C3 and C5 filter on
 * @param contactName    the field C4 probes with a starts-with match
 * @param creditworthy   whether the credit rating is GOOD; C5 combines it with a city. Deliberately a
 *                       boolean rather than a raw score, so the score threshold stays a domain rule in
 *                       each module instead of being duplicated here
 * @param annualRevenue  the numeric field C6 puts a range on
 * @param lastOrderDate  the date field C7 (relative) and C8 (explicit range) filter on
 */
public record CanonicalCustomer(long id, String city, String contactName, boolean creditworthy,
                                BigDecimal annualRevenue, LocalDate lastOrderDate) {
}
