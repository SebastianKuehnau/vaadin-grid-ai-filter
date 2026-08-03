package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.Customer;
import org.springframework.data.jpa.domain.Specification;

/**
 * The AI layer's seam towards the view: a natural-language query in, a JPA {@link Specification} out.
 * Implementations must never throw — on any failure they fall back to an unrestricted specification, so
 * a bad model response cannot break the UI.
 * <p>
 * One method wide on purpose: the prompt, the filter type and whether the model calls a tool or returns
 * an object all live behind it, in each module's own {@code CustomerSearchService}. It names no Spring AI
 * type, which is what lets {@code 01-non-ai-filter} depend on this module without resolving Spring AI.
 */
public interface CustomerSearchAgent {

    Specification<Customer> resolveFilter(String naturalLanguageQuery);
}
