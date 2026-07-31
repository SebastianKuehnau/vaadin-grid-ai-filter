package dev.demo.vaadin.aigridfilter.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the token measurement this module borrows from {@code demo-commons}.
 * <p>
 * {@link TokenUsageRecorder} is deliberately not a {@code @Component} — see its Javadoc for why, and why
 * every module that wants it says so explicitly here. {@link TokenUsageAdvisor} needs no bean: the
 * {@code CustomerSearchService} builds one in its constructor.
 */
@Configuration
class TokenUsageConfiguration {

    @Bean
    TokenUsageRecorder tokenUsageRecorder(MeterRegistry meterRegistry) {
        return new TokenUsageRecorder(meterRegistry);
    }
}
