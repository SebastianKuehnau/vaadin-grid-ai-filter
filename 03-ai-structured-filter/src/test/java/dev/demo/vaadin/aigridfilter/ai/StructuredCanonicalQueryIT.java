package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCanonicalQueryIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The canonical query set through structured output's service. Everything shared — configuration, token
 * bookkeeping, the assert-and-log step — is in {@link AbstractCanonicalQueryIT}; what this variant can
 * express is in {@link StructuredOutcomes}.
 */
class StructuredCanonicalQueryIT extends AbstractCanonicalQueryIT {

    @Autowired
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return StructuredOutcomes.of(canonical);
    }
}
