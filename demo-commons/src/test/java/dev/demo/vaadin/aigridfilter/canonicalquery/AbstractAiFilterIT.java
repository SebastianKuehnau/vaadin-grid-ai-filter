package dev.demo.vaadin.aigridfilter.canonicalquery;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures a module's AI filtering against a real model, through its {@link CustomerSearchAgent}. Both
 * query sets run here, and both are scored on the <b>resulting customer set</b> rather than on the shape of
 * the extracted filter: the returned {@code Specification} is executed against the seeded database and the
 * resulting ids are compared with the ids a reference predicate selects.
 * <p>
 * {@link #canonicalQuery} measures what a variant can <em>express</em> — the eight queries of
 * {@link CanonicalQuery}, each with the {@link ExpectedResult} this variant's filter type allows.
 * {@link #robustnessQuery} measures the opposite direction: input that asks for no filter at all must
 * produce an empty filter rather than a hallucinated condition. That does not depend on the filter type, so
 * there is no {@code ExpectedResult} for it and every variant is expected to pass all five — a failure
 * there is a genuine reliability finding, not a documented limit.
 * <p>
 * Everything that is the same for all four variants lives here — the Spring configuration, the token
 * bookkeeping, the assert-and-log step. A subclass supplies the only two things that differ: which agent to
 * ask, and which queries its filter type can express at all.
 * <p>
 * Run with {@code -Pit-local-ollama}, which targets a native Ollama instance at {@code OLLAMA_BASE_URL} by
 * default ({@code -DAI_TEST_PROFILE=openai} targets the real OpenAI API). No reachability probe — an
 * unreachable backend fails the run rather than skipping it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
// Generous on purpose, and per query rather than per class: an inexpressible query is also the slowest (the
// model keeps trying to place the part it had to drop) and a cold model load adds a few seconds. The suite
// should fail on wrong results, not on slowness; num-predict bounds the answer length.
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractAiFilterIT {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAiFilterIT.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TokenUsageAdvisor tokenUsageAdvisor;

    /** The variant under test: the agent whose filter the produced customer set comes from. */
    protected abstract CustomerSearchAgent agent();

    /** What this variant's filter type can express — the one thing that differs between the modules. */
    protected abstract ExpectedResult expectedResultFor(CanonicalQuery canonical);

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageAdvisor.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageAdvisor.logSummary(getClass().getSimpleName());
    }

    @ParameterizedTest
    @EnumSource(CanonicalQuery.class)
    void canonicalQuery(CanonicalQuery canonical) {
        List<Customer> seeded = customerRepository.findAll();
        Set<Long> actual = matchingIds(canonical.query());
        List<Set<Long>> acceptable = canonical.acceptableIdSets(seeded);
        ExpectedResult expectedResult = expectedResultFor(canonical);

        logger.info("{} [{}] '{}' -> {} of {} customers, acceptable sizes {}",
                canonical.name(), expectedResult, canonical.query(), actual.size(), seeded.size(),
                acceptable.stream().map(Set::size).toList());

        if (expectedResult == ExpectedResult.MATCH) {
            assertThat(acceptable)
                    .as("%s: the filtered customer set (%d rows) must equal one of the expected sets %s",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .contains(actual);
        } else {
            assertThat(acceptable)
                    .as("%s cannot be expressed by this variant's filter type, yet the filtered customer "
                                    + "set (%d rows) matched an expected set %s — an accidental capability "
                                    + "worth looking at, not a green test",
                            canonical.name(), actual.size(), acceptable.stream().map(Set::size).toList())
                    .doesNotContain(actual);
        }
    }

    @ParameterizedTest
    @EnumSource(RobustnessQuery.class)
    void robustnessQuery(RobustnessQuery robustness) {
        List<Customer> seeded = customerRepository.findAll();
        Set<Long> actual = matchingIds(robustness.query());
        Set<Long> expected = robustness.expectedIds(seeded);

        logger.info("{} '{}' -> {} of {} customers, expected {}",
                robustness.name(), robustness.query(), actual.size(), seeded.size(), expected.size());

        assertThat(actual)
                .as("%s: '%s' must select %d of the %d seeded customers",
                        robustness.name(), robustness.query(), expected.size(), seeded.size())
                .isEqualTo(expected);
    }

    /** This variant's mechanism: query in, ids of the customers its filter selects out. */
    private Set<Long> matchingIds(String query) {
        return customerRepository.findAll(agent().resolveFilter(query)).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
    }
}
