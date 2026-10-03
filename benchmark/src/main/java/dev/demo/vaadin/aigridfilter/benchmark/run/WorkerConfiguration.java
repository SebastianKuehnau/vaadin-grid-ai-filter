package dev.demo.vaadin.aigridfilter.benchmark.run;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;

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

    /** The two date moves every module's application class makes at startup, which this context never loads. */
    @Bean
    ApplicationRunner moveRelativeDateHits(JdbcTemplate jdbcTemplate) {
        String update = "UPDATE customer SET last_order_date = ? WHERE company_name = ?";
        return args -> {
            jdbcTemplate.update(update, LocalDate.now().minusDays(1), "Berlin Data Works");
            jdbcTemplate.update(update, LocalDate.now().minusWeeks(1).with(DayOfWeek.WEDNESDAY),
                    "Acme Manufacturing Frankfurt");
        };
    }
}
