package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.Customer;
import org.springframework.data.jpa.domain.Specification;

/**
 * The AI layer's seam towards the view: turns a natural-language query into a JPA
 * {@link Specification}. Implementations must never throw — on any failure they fall back to an
 * unrestricted specification, so the UI never breaks on a bad model response.
 * <p>
 * This interface is shared because it is the one thing all four AI variants agree on, and because it is
 * exactly one method wide: everything interesting — the prompt, the filter type, whether the model calls
 * a tool or returns an object — happens behind it, in each module's own {@code CustomerSearchService}.
 * Note that it names no Spring AI type at all, which is what lets {@code 01-non-ai-filter} depend on
 * this module without resolving Spring AI.
 */
public interface CustomerSearchAgent {

    Specification<Customer> resolveFilter(String naturalLanguageQuery);
}
