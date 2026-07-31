package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of variant 02(b)'s filter translation ({@link CustomerCriteria} -> JPA
 * {@code Specification}) against the seeded H2 database — no LLM, no Docker. Covers every operator
 * per field type, the {@code negate} flag, the AND-across-fields combination and the
 * null-matches-all cases.
 * <p>
 * The last test pins down 02(b)'s deliberate ceiling: one criterion per field means no range and no
 * multi-value OR, no matter which operator is chosen.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerSpecificationsTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(CustomerCriteria criteria) {
        return repository.findAll(CustomerSpecifications.from(criteria));
    }

    private static CustomerCriteria empty() {
        return new CustomerCriteria(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerCriteria city(String value, Operator operator, boolean negate) {
        return new CustomerCriteria(null, null, null, null, null, null, null,
                new FieldCriterion<>(value, operator, negate), null, null, null, null, null);
    }

    private static CustomerCriteria contactName(String value, Operator operator, boolean negate) {
        return new CustomerCriteria(null, new FieldCriterion<>(value, operator, negate),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerCriteria email(String value, Operator operator, boolean negate) {
        return new CustomerCriteria(null, null, new FieldCriterion<>(value, operator, negate),
                null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerCriteria lastOrderDate(LocalDate value, Operator operator, boolean negate) {
        return new CustomerCriteria(null, null, null, null, null,
                new FieldCriterion<>(value, operator, negate), null, null, null, null, null, null, null);
    }

    private static CustomerCriteria annualRevenue(BigDecimal value, Operator operator, boolean negate) {
        return new CustomerCriteria(null, null, null, null, null, null, null, null, null, null, null, null,
                new FieldCriterion<>(value, operator, negate));
    }

    private static CustomerCriteria cityAndRating(String city, CreditRating rating) {
        return new CustomerCriteria(null, null, null, null, null, null, null,
                new FieldCriterion<>(city, Operator.CONTAINS, false), null, null, null,
                new FieldCriterion<>(rating, Operator.EQUALS, false), null);
    }

    @Test
    void textContainsMatchesSubstringCaseInsensitively() {
        var result = findAll(city("berlin", Operator.CONTAINS, false));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin"));
    }

    @Test
    void textEqualsMatchesTheWholeValue() {
        var result = findAll(contactName("Sofia Meyer", Operator.EQUALS, false));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getContactName()).isEqualToIgnoringCase("Sofia Meyer"));
        // A substring of that name matches nothing under EQUALS - the operator really is exact.
        assertThat(findAll(contactName("Sofia", Operator.EQUALS, false))).isEmpty();
    }

    @Test
    void textStartsWithMatchesThePrefix() {
        var result = findAll(contactName("m", Operator.STARTS_WITH, false));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getContactName().toLowerCase()).startsWith("m"));
    }

    @Test
    void textEndsWithMatchesTheSuffix() {
        var result = findAll(contactName("schmidt", Operator.ENDS_WITH, false));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getContactName().toLowerCase()).endsWith("schmidt"));
    }

    @Test
    void negateExcludesTheMatches() {
        var berlin = findAll(city("berlin", Operator.CONTAINS, false));
        var notBerlin = findAll(city("berlin", Operator.CONTAINS, true));
        assertThat(berlin).isNotEmpty();
        assertThat(notBerlin).isNotEmpty();
        assertThat(notBerlin).allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase()).doesNotContain("berlin"));
        assertThat(berlin.size() + notBerlin.size()).isEqualTo((int) repository.count());
    }

    @Test
    void negateWorksOnOtherOperatorsToo() {
        var result = findAll(email(".example", Operator.ENDS_WITH, true));
        // Every seeded email ends with .example, so negating that suffix must match nothing.
        assertThat(result).isEmpty();
    }

    @Test
    void dateEqualsMatchesExactlyThatDay() {
        var result = findAll(lastOrderDate(LocalDate.of(2024, 7, 19), Operator.EQUALS, false));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getLastOrderDate()).isEqualTo(LocalDate.of(2024, 7, 19)));
    }

    @Test
    void dateBoundsAreDayLevelNotWholeYear() {
        LocalDate bound = LocalDate.of(2024, 7, 1);
        var since = findAll(lastOrderDate(bound, Operator.GREATER_OR_EQUAL, false));
        assertThat(since).isNotEmpty();
        assertThat(since).allSatisfy(c -> assertThat(c.getLastOrderDate()).isAfterOrEqualTo(bound));
        // The 2024 rows before that day are excluded - this is a real day bound, unlike variant 02(a).
        assertThat(since).noneMatch(c -> c.getLastOrderDate().getYear() == 2024
                && c.getLastOrderDate().isBefore(bound));

        var until = findAll(lastOrderDate(bound, Operator.LESS_OR_EQUAL, false));
        assertThat(until).isNotEmpty();
        assertThat(until).allSatisfy(c -> assertThat(c.getLastOrderDate()).isBeforeOrEqualTo(bound));
    }

    @Test
    void numberOperatorsCompareTheValue() {
        var atLeast = findAll(annualRevenue(BigDecimal.valueOf(200_000), Operator.GREATER_OR_EQUAL, false));
        assertThat(atLeast).isNotEmpty();
        assertThat(atLeast).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(200_000)));

        var atMost = findAll(annualRevenue(BigDecimal.valueOf(10_000), Operator.LESS_OR_EQUAL, false));
        assertThat(atMost).isNotEmpty();
        assertThat(atMost).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isLessThanOrEqualTo(BigDecimal.valueOf(10_000)));

        var exact = findAll(annualRevenue(BigDecimal.valueOf(100_000), Operator.EQUALS, false));
        assertThat(exact).isNotEmpty();
        assertThat(exact).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isEqualByComparingTo(BigDecimal.valueOf(100_000)));
    }

    @Test
    void creditRatingMapsToScoreBandAndCanBeNegated() {
        for (CreditRating rating : CreditRating.values()) {
            var result = findAll(new CustomerCriteria(null, null, null, null, null, null, null, null, null, null,
                    null, new FieldCriterion<>(rating, Operator.EQUALS, false), null));
            assertThat(result).isNotEmpty();
            assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isEqualTo(rating));
        }
        var notPoor = findAll(new CustomerCriteria(null, null, null, null, null, null, null, null, null, null,
                null, new FieldCriterion<>(CreditRating.POOR, Operator.EQUALS, true), null));
        assertThat(notPoor).isNotEmpty();
        assertThat(notPoor).allSatisfy(c -> assertThat(c.getCreditRating()).isNotEqualTo(CreditRating.POOR));
    }

    @Test
    void allGivenFieldsCombineWithAnd() {
        var result = findAll(cityAndRating("berlin", CreditRating.MEDIUM));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin");
            assertThat(c.getCreditRating()).isEqualTo(CreditRating.MEDIUM);
        });
        // Sanity check: some Berlin customers are NOT MEDIUM -> proves the AND is real.
        assertThat(findAll(city("berlin", Operator.CONTAINS, false)))
                .anyMatch(c -> c.getCreditRating() != CreditRating.MEDIUM);
    }

    @Test
    void nullCriteriaAndEmptyCriteriaMatchAll() {
        assertThat(findAll(null)).hasSize((int) repository.count());
        assertThat(findAll(empty())).hasSize((int) repository.count());
    }

    @Test
    void cannotExpressARangeOnOneField() {
        // 02(b)'s ceiling: one value plus one operator per field, so a lower AND an upper bound on the
        // same field is impossible - whichever bound is chosen, the other end stays unfiltered.
        var lowerBoundOnly = findAll(annualRevenue(BigDecimal.valueOf(100_000), Operator.GREATER_OR_EQUAL, false));
        assertThat(lowerBoundOnly).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) > 0);

        var upperBoundOnly = findAll(annualRevenue(BigDecimal.valueOf(200_000), Operator.LESS_OR_EQUAL, false));
        assertThat(upperBoundOnly).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(100_000)) < 0);
    }
}
