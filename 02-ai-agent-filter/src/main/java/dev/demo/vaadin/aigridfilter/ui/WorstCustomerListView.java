package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

@Route("worst")
public class WorstCustomerListView extends VerticalLayout {

    private static final Logger logger =
            LoggerFactory.getLogger(WorstCustomerListView.class);

    public final CustomerGrid grid = new CustomerGrid();
    public final TextField filterField;

    private final CustomerRepository customerRepository;
    private final ChatClient chatClient;

    public WorstCustomerListView(
            CustomerRepository customerRepository,
            ChatClient.Builder chatClientBuilder) {

        this.customerRepository = customerRepository;
        this.chatClient = chatClientBuilder
                // This view sends only 10 customers into the prompt; we need to raise the tokens to 8192.
                .defaultOptions(OllamaChatOptions.builder()
                        .numCtx(8192)
                        .numPredict(512))
                .build();

        filterField = new TextField("", "Search customers...");
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();

        // Avoid calling Ollama after every single keystroke.
        filterField.addValueChangeListener(event -> {
            String query = event.getValue();

            if (query == null || query.isBlank()) {
                grid.setItems(getAllCustomers());
                return;
            }

            search(query);
        });

        add(filterField);

        grid.setItems(getAllCustomers());

        add(grid);

        setSizeFull();
    }

    private void search(String query) {

        logger.info("Searching customers for: {}", query);

        String response = chatClient.prompt()
                .system("""
                        You filter customers based on a natural-language query.

                        Follow these steps:
                        1. Call getAllCustomers to retrieve the available customers.
                        2. Determine which customers match the user's query.
                        3. Call showCustomers with the IDs of ALL matching customers.

                        Always call showCustomers, even if no customers match.
                        Do not explain the result to the user.
                        """)
                .user(query)
                .tools(this)
                .call()
                .content();

        logger.debug("LLM response: {}", response);
    }

    @Tool(description = """
            Returns all available customers that can be searched.

            Customer properties include:
            id,
            companyName,
            contactName,
            email,
            phoneNumber,
            address.country,
            address.city,
            address.street,
            annualRevenue,
            creditScore,
            customerSince,
            lastOrder.
            """)
    List<Customer> getAllCustomers() {
        logger.debug("Getting all customers");
        return customerRepository.findAll(PageRequest.of(0, 10)).getContent();
    }

    @Tool(description = """
            Display the customers that match the user's search query.
            Call this tool with the IDs of all relevant customers.
            If no customer matches, pass an empty set.
            """)
    void showCustomers(
            @ToolParam(description = "IDs of all customers matching the search query")
            Set<Long> relevantIds) {
        logger.debug("Showing customers with IDs: {}", relevantIds);

        List<Customer> relevantCustomers = customerRepository.findAll()
                .stream()
                .filter(customer -> relevantIds.contains(customer.getId()))
                .toList();

        grid.setItems(relevantCustomers);
    }
}