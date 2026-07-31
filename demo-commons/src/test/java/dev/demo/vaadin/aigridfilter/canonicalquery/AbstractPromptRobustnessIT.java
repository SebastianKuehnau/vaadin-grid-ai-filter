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
 * Runs the {@link RobustnessQuery} set against a real model. No {@link Outcome} mapping and no abstract
 * hook for one: none of these cases needs a filter type, so every variant is expected to pass all five —
 * a failure here is a genuine reliability finding, not a documented limit.
 * <p>
 * That makes it the counterpart to {@link AbstractCanonicalQueryIT}, which measures what a variant can
 * express. This one measures whether the prompt holds up when the input is not a filter, and the answer
 * should be the same in every module.
 * <p>
 * Run with {@code -Pit-local-ollama}, which targets a native Ollama instance at {@code OLLAMA_BASE_URL} by
 * default ({@code -DAI_TEST_PROFILE=openai} targets the real OpenAI API).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractPromptRobustnessIT {

    private static final Logger logger = LoggerFactory.getLogger(AbstractPromptRobustnessIT.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TokenUsageAdvisor tokenUsageAdvisor;

    /** The variant under test — the only thing a subclass has to supply. */
    protected abstract CustomerSearchAgent agent();

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageAdvisor.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageAdvisor.logSummary(getClass().getSimpleName());
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

    private Set<Long> matchingIds(String query) {
        return customerRepository.findAll(agent().resolveFilter(query)).stream()
                .map(Customer::getId)
                .collect(Collectors.toSet());
    }
}
