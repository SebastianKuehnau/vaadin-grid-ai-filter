package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;

/**
 * Per-invocation holder that {@link CustomerSearchTools#searchCustomers} writes the extracted
 * criteria into. A fresh instance is created for every {@link ToolCallingCustomerSearchAgent}
 * call, so there is no shared state between concurrent searches.
 */
class CustomerSearchResult {

    CustomerSearchCriteria criteria;
}
