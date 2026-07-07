package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain JUnit test (no Spring context, no LLM) of the tool's own correctness, in isolation. */
class CurrentDateTimeToolTest {

    @Test
    void returnsTheCurrentDateTime() {
        LocalDateTime result = new CurrentDateTimeTool().currentLocalDateTime();
        assertThat(Duration.between(result, LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
