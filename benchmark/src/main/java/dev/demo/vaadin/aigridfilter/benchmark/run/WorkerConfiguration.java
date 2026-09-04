package dev.demo.vaadin.aigridfilter.benchmark.run;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The worker's Spring context: the domain layer plus whichever AI services are on this JVM's classpath.
 *
 * <p>The scan deliberately covers only {@code ...aigridfilter.ai}, so no {@code @Route} view and no
 * Vaadin class is ever loaded — that is what keeps a worker small.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan("dev.demo.vaadin.aigridfilter.ai")
@EntityScan("dev.demo.vaadin.aigridfilter.data")
@EnableJpaRepositories("dev.demo.vaadin.aigridfilter.data")
class WorkerConfiguration {

    /** Primary, so every service's {@code ChatModel} parameter gets the budgeted one, unchanged. */
    @Bean
    @Primary
    CallBudgetChatModel callBudgetChatModel(OllamaChatModel delegate,
            @Value("${benchmark.worker.max-model-calls-per-query}") int maxCallsPerQuery) {
        return new CallBudgetChatModel(delegate, maxCallsPerQuery);
    }
}
