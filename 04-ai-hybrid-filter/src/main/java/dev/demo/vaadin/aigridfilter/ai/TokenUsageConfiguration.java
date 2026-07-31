package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the token measurement this module borrows from {@code demo-commons}.
 * <p>
 * {@link TokenUsageAdvisor} is deliberately not a {@code @Component} — see its Javadoc for why, and why
 * every module that wants it says so explicitly here.
 */
@Configuration
class TokenUsageConfiguration {

    @Bean
    TokenUsageAdvisor tokenUsageAdvisor() {
        return new TokenUsageAdvisor();
    }
}
