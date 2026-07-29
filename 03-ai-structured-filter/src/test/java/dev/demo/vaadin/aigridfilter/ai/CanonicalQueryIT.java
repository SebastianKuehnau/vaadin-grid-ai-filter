package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the canonical query set (see {@code docs/canonical-query-set.md}) against a real Ollama and
 * scores every query on the <b>resulting customer set</b>, not on the shape of the extracted filter: the
 * query goes through structured output ({@code .responseEntity(CustomerFilter.class)}), the returned {@code Specification} is executed against the seeded
 * database, and the resulting ids are compared with the ids a reference predicate selects.
 * <p>
 * A {@code CustomerFilter} is a flat list of conditions, each with several values (OR within a field)
 * and a negate flag, and a range is two sibling conditions on one field — so all eight categories are
 * expressible. {@code 04-ai-hybrid-filter} runs this very same set against the very same filter type,
 * delivered as a tool call instead; any divergence between the two is model or mechanism behaviour,
 * never a difference in what the filter can express.
 * <p>
 * Queries this variant cannot express are marked {@link Outcome#FAILS_BY_DESIGN} and asserted to produce
 * a customer set that differs from the expected one — a documented, non-erroring failure. Should such a
 * case ever match the expected set, the test fails: an accidental capability is as much of a finding as a
 * missing one.
 * <p>
 * Run with {@code -Pit-local-ollama}, which targets a native Ollama instance at {@code OLLAMA_BASE_URL}
 * by default (pass {@code -DAI_TEST_PROFILE=openai} to target the real OpenAI API instead). There is no
 * reachability probe — if the backend isn't reachable, the run fails rather than skipping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonicalQueryIT {

    private static final Logger logger = LoggerFactory.getLogger(CanonicalQueryIT.class);

    /** Whether this variant's filter type can express a query at all. */
    enum Outcome {
        PASSES, FAILS_BY_DESIGN
    }

    /**
     * The canonical query set. {@code docs/canonical-query-set.md} is the single source of truth for
     * every query string below — {@code CanonicalQuerySetConsistencyTest} fails the build if this enum
     * and that document drift apart, in wording or in order.
     * <p>
     * Each constant carries the query, the {@link Outcome} expected of <em>this</em> variant, and the
     * customer set(s) that count as correct — always as a predicate over the seeded data, never as a
     * hard-coded id list, because C7 depends on today's date.
     */
    enum CanonicalQuery {

        /** C1 — single value. */
        C1_SINGLE_VALUE("show me all customers in Berlin", Outcome.PASSES,
                customer -> containsIgnoringCase(customer.getAddress().getCity(), "Berlin")),

        /** C2 — multiple values for one field (OR). */
        C2_MULTI_VALUE_OR("show me customers from Berlin or Hamburg", Outcome.PASSES,
                customer -> containsIgnoringCase(customer.getAddress().getCity(), "Berlin")
                        || containsIgnoringCase(customer.getAddress().getCity(), "Hamburg")),

        /** C3 — negation. */
        C3_NEGATION("show me all customers except from Berlin", Outcome.PASSES,
                customer -> !containsIgnoringCase(customer.getAddress().getCity(), "Berlin")),

        /** C4 — non-CONTAINS operator (starts-with). */
        C4_OPERATOR_PRECISION("show me all customers with an \"m\" as the first character in the contact name",
                Outcome.PASSES,
                customer -> startsWithIgnoringCase(customer.getContactName(), "m")),

        /** C5 — combined AND across fields. */
        C5_COMBINED_AND("creditworthy customers in Hamburg", Outcome.PASSES,
                customer -> containsIgnoringCase(customer.getAddress().getCity(), "Hamburg")
                        && customer.getCreditRating() == CreditRating.GOOD),

        /** C6 — revenue range. */
        C6_REVENUE_RANGE("customers with revenue between 100000 and 200000", Outcome.PASSES,
                customer -> isBetween(customer.getAnnualRevenue(), 100_000, 200_000)),

        /**
         * C7 — relative date. Both readings of "the last 12 months" are accepted: the open-ended one and
         * the one that also excludes the single future-dated order in the seed data.
         */
        C7_RELATIVE_DATE("show me all customers who placed an order in the last 12 months", Outcome.PASSES,
                List.of(customer -> !customer.getLastOrderDate().isBefore(LocalDate.now().minusYears(1)),
                        customer -> !customer.getLastOrderDate().isBefore(LocalDate.now().minusYears(1))
                                && !customer.getLastOrderDate().isAfter(LocalDate.now()))),

        /** C8 — date range, spanning two calendar years on purpose. */
        C8_DATE_RANGE("customers who last ordered between 2024-07-01 and 2025-03-31", Outcome.PASSES,
                customer -> isBetween(customer.getLastOrderDate(),
                        LocalDate.of(2024, 7, 1), LocalDate.of(2025, 3, 31)));

        private final String query;
        private final Outcome outcome;
        private final List<Predicate<Customer>> acceptableExpectations;

        CanonicalQuery(String query, Outcome outcome, Predicate<Customer> expected) {
            this(query, outcome, List.of(expected));
        }

        CanonicalQuery(String query, Outcome outcome, List<Predicate<Customer>> acceptableExpectations) {
            this.query = query;
            this.outcome = outcome;
            this.acceptableExpectations = acceptableExpectations;
        }

        String query() {
            return query;
        }

        Outcome outcome() {
            return outcome;
        }

        /** The customer-id sets that count as a correct answer for this query, in the given data. */
        List<Set<Long>> acceptableIdSets(List<Customer> allCustomers) {
            return acceptableExpectations.stream()
                    .map(expected -> allCustomers.stream().filter(expected)
                            .map(Customer::getId).collect(Collectors.toSet()))
                    .toList();
        }
    }

    @Autowired
    CustomerSearchStructuredOutputService agent;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    TokenUsageRecorder tokenUsageRecorder;

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageRecorder.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageRecorder.logSummary("CanonicalQueryIT");
    }

    @ParameterizedTest
    @EnumSource(CanonicalQuery.class)
    void canonicalQuery(CanonicalQuery canonical) {
        List<Customer> allCustomers = customerRepository.findAll();
        Set<Long> actual = customerRepository.findAll(agent.resolveFilter(canonical.query())).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
        List<Set<Long>> acceptable = canonical.acceptableIdSets(allCustomers);

        logger.info("{} [{}] '{}' -> {} of {} customers, acceptable sizes {}",
                canonical.name(), canonical.outcome(), canonical.query(), actual.size(), allCustomers.size(),
                acceptable.stream().map(Set::size).toList());

        if (canonical.outcome() == Outcome.PASSES) {
            assertThat(acceptable)
                    .as("%s: the filtered customer set (%d rows) must equal one of the expected sets %s",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .contains(actual);
        } else {
            assertThat(acceptable)
                    .as("%s cannot be expressed by this variant's filter type, yet the filtered customer "
                            + "set (%d rows) matched an expected set %s — an accidental capability worth "
                            + "looking at, not a green test",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .doesNotContain(actual);
        }
    }

    private static boolean containsIgnoringCase(String value, String part) {
        return value != null && value.toLowerCase().contains(part.toLowerCase());
    }

    private static boolean startsWithIgnoringCase(String value, String prefix) {
        return value != null && value.toLowerCase().startsWith(prefix.toLowerCase());
    }

    private static boolean isBetween(BigDecimal value, long lowerInclusive, long upperInclusive) {
        return value != null && value.compareTo(BigDecimal.valueOf(lowerInclusive)) >= 0
                && value.compareTo(BigDecimal.valueOf(upperInclusive)) <= 0;
    }

    private static boolean isBetween(LocalDate value, LocalDate lowerInclusive, LocalDate upperInclusive) {
        return value != null && !value.isBefore(lowerInclusive) && !value.isAfter(upperInclusive);
    }
}
