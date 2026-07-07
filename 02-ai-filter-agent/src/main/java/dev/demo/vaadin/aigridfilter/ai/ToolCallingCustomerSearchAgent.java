package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSpecifications;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * The AI layer: turns a natural-language query into a JPA {@link Specification} by letting the
 * model call the {@code searchCustomers} tool and extract the filter values. The class owns the
 * {@link ChatClient} and the system prompt and knows nothing about Vaadin, so it can be tested in
 * isolation.
 */
@Service
class ToolCallingCustomerSearchAgent implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallingCustomerSearchAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that helps users find customers based on their
            company name, contact name, email, phone, customer since, last order date,
            country, city, postal code, street, house number, and credit rating.
            The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
            at risk / not creditworthy (POOR).
            """;

    private final ChatClient chatClient;

    ToolCallingCustomerSearchAgent(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM to call the search tool
     * ({@link #requestCriteria}) and translate the extracted criteria. {@code null} criteria (e.g.
     * on a bad response, or the model never called the tool) matches all.
     */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerSpecifications.from(requestCriteria(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to call {@code searchCustomers} and returns the criteria it extracted.
     * Package-private so the AI layer can be tested directly on the produced criteria. Returns
     * {@code null} if the model produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerSearchCriteria requestCriteria(String naturalLanguageQuery) {
        CustomerSearchResult result = new CustomerSearchResult();
        try {
            chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(naturalLanguageQuery)
                    .tools(new CustomerSearchTools(result), new CurrentDateTimeTool())
                    .advisors(SimpleLoggerAdvisor.builder().build())
                    .call()
                    .content();
        } catch (Exception e) {
            logger.warn("Could not turn query into search criteria; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return null;
        }
        logger.info("requestCriteria('{}') -> {}", naturalLanguageQuery, result.criteria);
        return result.criteria;
    }
}
