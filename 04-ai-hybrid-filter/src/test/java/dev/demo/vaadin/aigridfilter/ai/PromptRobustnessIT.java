package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractPromptRobustnessIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Prompt robustness of variant 04: what its AI layer does with input that asks for no filter, and
 * with a query in German. All five cases are expected to pass — see {@link AbstractPromptRobustnessIT}.
 */
class PromptRobustnessIT extends AbstractPromptRobustnessIT {

    @Autowired
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }
}
