package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the token measurement this module borrows from {@code demo-commons}.
 * {@link TokenUsageAdvisor} carries no {@code @Component} — see its Javadoc for why every module that
 * wants it has to say so here.
 */
@Configuration
class TokenUsageConfiguration {

    @Bean
    TokenUsageAdvisor tokenUsageAdvisor() {
        return new TokenUsageAdvisor();
    }
}
