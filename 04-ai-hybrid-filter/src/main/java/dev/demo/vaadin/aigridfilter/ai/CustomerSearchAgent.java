package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.Customer;
import org.springframework.data.jpa.domain.Specification;

/**
 * The AI layer's seam towards the view: turns a natural-language query into a JPA
 * {@link Specification}. Implementations must never throw — on any failure they fall back to an
 * unrestricted specification, so the UI never breaks on a bad model response.
 */
public interface CustomerSearchAgent {

    Specification<Customer> resolveFilter(String naturalLanguageQuery);
}
