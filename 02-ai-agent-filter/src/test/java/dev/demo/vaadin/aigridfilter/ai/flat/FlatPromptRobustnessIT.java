package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractPromptRobustnessIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Prompt robustness of variant 02(a): what its AI layer does with input that asks for no filter, and
 * with a query in German. All five cases are expected to pass — see {@link AbstractPromptRobustnessIT}.
 */
class FlatPromptRobustnessIT extends AbstractPromptRobustnessIT {

    @Autowired
    @Qualifier("flatSearchAgent")
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }
}
