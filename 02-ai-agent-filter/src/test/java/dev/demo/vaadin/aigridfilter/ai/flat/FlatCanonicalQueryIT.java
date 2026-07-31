package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCanonicalQueryIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * The canonical query set through variant 02(a)'s service. Everything shared — configuration, token
 * bookkeeping, the assert-and-log step — is in {@link AbstractCanonicalQueryIT}; what this variant can
 * express is in {@link FlatOutcomes}.
 */
class FlatCanonicalQueryIT extends AbstractCanonicalQueryIT {

    @Autowired
    @Qualifier("flatSearchAgent")
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return FlatOutcomes.of(canonical);
    }
}
