package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageRecorder;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalCustomer;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQueryRunner;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;
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

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs the canonical query set (see {@code docs/canonical-query-set.md}) against a real Ollama and
 * scores every query on the <b>resulting customer set</b>, not on the shape of the extracted filter: the
 * query goes through variant 02(a).s flat tool call, the returned {@code Specification} is executed against the seeded
 * database, and the resulting ids are compared with the ids a reference predicate selects.
 * <p>
 * Variant 02(a) has one scalar value per field and no operator at all, so only the two simplest
 * categories are within reach: a single value (C1) and an AND across fields (C5). Multi-value OR,
 * negation, operator precision and every kind of range or date bound are architecturally
 * impossible — its tool has no parameter that could carry them.
 * <p>
 * The queries, their expected result sets and the assert/log step are shared with every other AI
 * module's canonical-query IT and live in {@code canonical-query-testkit}. What is specific to this
 * variant — and therefore right here — is {@link #outcomeOf} and {@link #matchingIds}.
 * <p>
 * Run with {@code -Pit-local-ollama}, which targets a native Ollama instance at {@code OLLAMA_BASE_URL}
 * by default (pass {@code -DAI_TEST_PROFILE=openai} to target the real OpenAI API instead). There is no
 * reachability probe — if the backend isn't reachable, the run fails rather than skipping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
// Generous on purpose: a query a variant cannot express is also its slowest (the model keeps trying to
// place the part it had to drop), and a cold model load costs another few seconds. Answer length is
// bounded by num-predict in application-ollama.properties, so this timeout only has to absorb the round
// trips — the suite should fail on wrong results, not on slowness.
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlatCanonicalQueryIT {

    private static final Logger logger = LoggerFactory.getLogger(FlatCanonicalQueryIT.class);

    /**
     * What variant 02(a) can express — the whole point of this IT, and the only thing that differs from
     * the other modules' canonical-query ITs.
     * <p>
     * An exhaustive {@code switch} rather than a map: adding a query to the shared set without deciding
     * what it means for this variant then fails to compile instead of failing at runtime.
     */
    private static Outcome outcomeOf(CanonicalQuery canonical) {
        return switch (canonical) {
            // One scalar value per field, combined with AND — that is the whole capability.
            case C1_SINGLE_VALUE, C5_COMBINED_AND -> Outcome.PASSES;
            // No operator and no second value per field, so none of these can be carried at all.
            case C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION, C6_REVENUE_RANGE,
                 C7_RELATIVE_DATE, C8_DATE_RANGE -> Outcome.FAILS_BY_DESIGN;
        };
    }

    @Autowired
    FlatToolCallingService agent;

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
        tokenUsageRecorder.logSummary("FlatCanonicalQueryIT");
    }

    @ParameterizedTest
    @EnumSource(CanonicalQuery.class)
    void canonicalQuery(CanonicalQuery canonical) {
        CanonicalQueryRunner.check(canonical, outcomeOf(canonical), seededCustomers(), this::matchingIds, logger);
    }

    /** This variant's mechanism: query in, ids of the customers its filter selects out. */
    private Set<Long> matchingIds(String query) {
        return customerRepository.findAll(agent.resolveFilter(query)).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
    }

    /** The seeded data, projected onto the fields the canonical queries filter on. */
    private List<CanonicalCustomer> seededCustomers() {
        return customerRepository.findAll().stream()
                .map(customer -> new CanonicalCustomer(customer.getId(), customer.getAddress().getCity(),
                        customer.getContactName(), customer.getCreditRating() == CreditRating.GOOD,
                        customer.getAnnualRevenue(), customer.getLastOrderDate()))
                .toList();
    }
}
