package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The robustness cases the canonical query set deliberately does not cover: what the AI layer does with
 * input that asks for <em>no</em> filter at all, and with a query in German.
 * <p>
 * C1-C8 all describe a filter and are scored on the customer set it produces. None of them probes the
 * opposite direction — that small talk, an unrelated question or an explicit "show everything" must
 * produce an <em>empty</em> filter rather than a hallucinated condition. That is the failure mode a live
 * demo runs into first, so it stays covered here after 03's pre-canonical ITs were retired.
 * <p>
 * Every expectation is computed from the seeded data, never hard-coded, so it survives edits to
 * {@code data.sql}. Scored on the resulting customer set like the canonical IT, not on the shape of the
 * extracted filter: an empty filter and a filter whose conditions happen to match everything are equally
 * acceptable answers to "show me all customers".
 * <p>
 * Run with {@code -Pit-local-ollama}, which targets a native Ollama instance at {@code OLLAMA_BASE_URL}
 * by default (pass {@code -DAI_TEST_PROFILE=openai} to target the real OpenAI API instead). There is no
 * reachability probe — if the backend isn't reachable, the run fails rather than skipping.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptRobustnessIT {

    private static final Logger logger = LoggerFactory.getLogger(PromptRobustnessIT.class);

    @Autowired
    CustomerSearchService agent;

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
        tokenUsageRecorder.logSummary("PromptRobustnessIT");
    }

    @Test
    void smallTalkFiltersNothing() {
        assertMatchesEveryCustomer("Nice weather today, isn't it?");
    }

    @Test
    void unrelatedRequestFiltersNothing() {
        assertMatchesEveryCustomer("What's the capital of France?");
    }

    @Test
    void showAllFiltersNothing() {
        assertMatchesEveryCustomer("show me all customers");
    }

    @Test
    void askingToResetFiltersNothing() {
        assertMatchesEveryCustomer("remove the filter and show everything again");
    }

    @Test
    void germanQueryFiltersTheSameAsItsEnglishEquivalent() {
        // "Kunden aus Berlin" is C1 asked in German; the prompt is English, the query is not.
        Set<Long> actual = matchingIds("zeig mir alle Kunden aus Berlin");
        Set<Long> berlinCustomers = idsWhere(customer -> "Berlin".equals(customer.getAddress().getCity()));

        logger.info("germanQueryFiltersTheSameAsItsEnglishEquivalent -> {} of {} customers, expected {}",
                actual.size(), customerRepository.count(), berlinCustomers.size());
        assertThat(actual).isEqualTo(berlinCustomers);
    }

    /** Asserts the query leaves the grid unfiltered — the answer to "no filter was asked for". */
    private void assertMatchesEveryCustomer(String query) {
        Set<Long> actual = matchingIds(query);
        Set<Long> allCustomers = idsWhere(_ -> true);

        logger.info("'{}' -> {} of {} customers, expected all", query, actual.size(), allCustomers.size());
        assertThat(actual).isEqualTo(allCustomers);
    }

    private Set<Long> matchingIds(String query) {
        return customerRepository.findAll(agent.resolveFilter(query)).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
    }

    private Set<Long> idsWhere(java.util.function.Predicate<Customer> predicate) {
        return customerRepository.findAll().stream()
                .filter(predicate)
                .map(Customer::getId)
                .collect(Collectors.toSet());
    }
}
