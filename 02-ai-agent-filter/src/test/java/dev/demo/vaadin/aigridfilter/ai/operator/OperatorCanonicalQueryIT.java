package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCanonicalQueryIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * The canonical query set through variant 02(b)'s service. Everything shared — configuration, token
 * bookkeeping, the assert-and-log step — is in {@link AbstractCanonicalQueryIT}; what this variant can
 * express is in {@link OperatorOutcomes}.
 */
class OperatorCanonicalQueryIT extends AbstractCanonicalQueryIT {

    @Autowired
    @Qualifier("operatorSearchAgent")
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return OperatorOutcomes.of(canonical);
    }
}
