package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * Runs the canonical query set against a real Ollama and scores every query on the <b>resulting
 * customer set</b> rather than on the shape of the extracted filter: the query goes through
 * structured output ({@code .entity(CustomerFilter.class)}).
 * The returned {@code Specification} is executed against the seeded database, and the resulting ids
 * are compared with the ids a reference predicate selects.
 * <p>
 * A {@code CustomerFilter} is a flat list of conditions, each with several values (OR within a field)
 * and a negate flag, and a range is two sibling conditions on one field — so all eight categories are
 * expressible. {@code 04-ai-hybrid-filter} runs this very same set against the very same filter type,
 * delivered as a tool call instead; any divergence between the two is model or mechanism behaviour,
 * never a difference in what the filter can express.
 * <p>
 * The eight cases below are {@code docs/canonical-query-set.md}, spelled out here rather than shared so
 * this class stands on its own. Every AI module's IT carries the same eight verbatim, and
 * {@code demo-commons}' {@code CanonicalQuerySetConsistencyTest} fails the build the moment one drifts.
 * <p>
 * Run with {@code -Pit-local-ollama} (native Ollama at {@code OLLAMA_BASE_URL};
 * {@code -DAI_TEST_PROFILE=openai} targets the real API). No reachability probe — an unreachable backend
 * fails the run rather than skipping it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
// Generous on purpose: an inexpressible query is also the slowest (the model keeps trying to place the
// part it had to drop) and a cold model load adds a few seconds. The suite should fail on wrong results,
// not on slowness; num-predict bounds the answer length.
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuredCanonicalQueryIT {

    private static final Logger logger = LoggerFactory.getLogger(StructuredCanonicalQueryIT.class);

    /** Reads better than a bare boolean in the table below. */
    private static final boolean EXPRESSIBLE = true;

    /** Query verbatim, whether this filter type can express it, and the acceptable result sets. */
    private record Case(String name, boolean expressible, String query,
                        List<Predicate<Customer>> acceptable) {

        Case(String name, boolean expressible, String query, Predicate<Customer> acceptable) {
            this(name, expressible, query, List.of(acceptable));
        }
    }

    private static List<Case> cases() {
        return List.of(
                new Case("C1_SINGLE_VALUE", EXPRESSIBLE,
                        "show me all customers in Berlin",
                        c -> c.getAddress().getCity().toLowerCase().contains("berlin")),
                new Case("C2_MULTI_VALUE_OR", EXPRESSIBLE,
                        "show me customers from Berlin or Hamburg",
                        c -> c.getAddress().getCity().toLowerCase().contains("berlin")
                                || c.getAddress().getCity().toLowerCase().contains("hamburg")),
                new Case("C3_NEGATION", EXPRESSIBLE,
                        "show me all customers except from Berlin",
                        c -> !c.getAddress().getCity().toLowerCase().contains("berlin")),
                new Case("C4_OPERATOR_PRECISION", EXPRESSIBLE,
                        "show me all customers with an \"m\" as the first character in the contact name",
                        c -> c.getContactName().toLowerCase().startsWith("m")),
                new Case("C5_COMBINED_AND", EXPRESSIBLE,
                        "creditworthy customers in Hamburg",
                        c -> c.getAddress().getCity().toLowerCase().contains("hamburg")
                                && c.getCreditRating() == CreditRating.GOOD),
                new Case("C6_REVENUE_RANGE", EXPRESSIBLE,
                        "customers with revenue between 100000 and 200000",
                        c -> isBetween(c.getAnnualRevenue(), 100_000, 200_000)),
                new Case("C7_RELATIVE_DATE", EXPRESSIBLE,
                        "show me all customers who placed an order in the last 12 months",
                        List.of(
                                c -> !c.getLastOrderDate().isBefore(LocalDate.now().minusYears(1)),
                                c -> !c.getLastOrderDate().isBefore(LocalDate.now().minusYears(1))
                                && !c.getLastOrderDate().isAfter(LocalDate.now()))),
                new Case("C8_DATE_RANGE", EXPRESSIBLE,
                        "customers who last ordered between 2024-07-01 and 2025-03-31",
                        c -> !c.getLastOrderDate().isBefore(LocalDate.of(2024, 7, 1))
                                && !c.getLastOrderDate().isAfter(LocalDate.of(2025, 3, 31))));
    }

    @Autowired
    CustomerSearchService agent;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    TokenUsageAdvisor tokenUsageAdvisor;

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageAdvisor.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageAdvisor.logSummary("CanonicalQueryIT");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void canonicalQuery(Case canonical) {
        List<Customer> seeded = customerRepository.findAll();
        Set<Long> actual = customerRepository.findAll(agent.resolveFilter(canonical.query())).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
        List<Set<Long>> acceptable = canonical.acceptable().stream()
                .map(expected -> seeded.stream().filter(expected)
                        .map(Customer::getId).collect(Collectors.toSet()))
                .toList();

        logger.info("{} [{}] '{}' -> {} of {} customers, acceptable sizes {}",
                canonical.name(), canonical.expressible() ? "PASSES" : "FAILS_BY_DESIGN",
                canonical.query(), actual.size(), seeded.size(),
                acceptable.stream().map(Set::size).toList());

        if (canonical.expressible()) {
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

    private static boolean isBetween(BigDecimal value, long lowerInclusive, long upperInclusive) {
        return value.compareTo(BigDecimal.valueOf(lowerInclusive)) >= 0
                && value.compareTo(BigDecimal.valueOf(upperInclusive)) <= 0;
    }
}
