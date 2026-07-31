package dev.demo.vaadin.aigridfilter.canonicalquery;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The slice of a customer the canonical query set actually filters on — and nothing else.
 * <p>
 * The modules do share one {@code Customer} entity now (it lives in {@code demo-commons}), so this record
 * is no longer about reaching across four copies. It stays for two better reasons: it keeps this testkit
 * free of JPA — nothing here depends on Hibernate or a repository, so the expectations are plain data —
 * and it holds {@code creditworthy} as a boolean rather than a raw score, which keeps the score threshold
 * a rule each module states for itself. Every module maps {@code Customer} onto this projection in a
 * handful of lines, and {@link CanonicalQuery}'s expected result sets are expressed over it.
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
