package dev.demo.vaadin.aigridfilter.canonicalquery;

import dev.demo.vaadin.aigridfilter.data.Customer;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The robustness cases the canonical query set deliberately leaves out: input that asks for <em>no</em>
 * filter at all, and a query in a language the system prompt is not written in.
 * <p>
 * C1–C8 all describe a filter and differ in how hard they are to express. These five differ in something
 * else — they probe the opposite direction, that a query without a filter in it must produce an
 * <em>empty</em> filter rather than a hallucinated condition. That is the failure mode a live demo runs
 * into first, and unlike the canonical set it does not depend on the filter type, so every AI module is
 * expected to pass all five.
 * <p>
 * Expectations are predicates over {@link Customer} evaluated against the seeded data, never hard-coded
 * ids. Scored on the resulting customer set, so an empty filter and a filter whose conditions happen to
 * match everything are equally acceptable answers to "show me all customers".
 */
public enum RobustnessQuery {

    /** Small talk: no filter is being asked for. */
    SMALL_TALK("Nice weather today, isn't it?", customer -> true),

    /** A question about something else entirely. */
    UNRELATED_QUESTION("What's the capital of France?", customer -> true),

    /** An explicit request for everything. */
    SHOW_ALL("show me all customers", customer -> true),

    /** An explicit request to clear the filter — the way a user undoes a search. */
    RESET_FILTER("remove the filter and show everything again", customer -> true),

    /** C1 asked in German: the system prompt is English, the query is not. */
    GERMAN_QUERY("zeig mir alle Kunden aus Berlin",
            customer -> customer.getAddress() != null && "Berlin".equals(customer.getAddress().getCity()));

    private final String query;
    private final Predicate<Customer> expected;

    RobustnessQuery(String query, Predicate<Customer> expected) {
        this.query = query;
        this.expected = expected;
    }

    /** The natural-language query, verbatim. */
    public String query() {
        return query;
    }

    /** The customer ids a correct answer selects, in the given data. */
    public Set<Long> expectedIds(List<Customer> allCustomers) {
        return allCustomers.stream().filter(expected).map(Customer::getId).collect(Collectors.toSet());
    }
}
