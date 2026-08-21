package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.Customer;
import org.springframework.data.jpa.domain.Specification;

/** The AI layer's seam towards the view: a natural-language query in, a JPA {@link Specification} out. */
public interface CustomerSearchAgent {

    Specification<Customer> resolveFilter(String naturalLanguageQuery);
}
