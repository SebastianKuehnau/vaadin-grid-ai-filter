package dev.demo.vaadin.aigridfilter.benchmark.cases;

import dev.demo.vaadin.aigridfilter.data.Customer;

import java.util.function.Predicate;

/**
 * One measured query: the prompt, and which customers a correct answer selects from the seeded data.
 *
 * <p>Most cases have one exact answer, so {@code mustMatch} and {@code mayMatch} are the same
 * predicate. Where the IT class accepts a range of answers (C7), {@code mustMatch} is the smallest
 * correct set and {@code mayMatch} the largest one.
 */
public record BenchmarkCase(String id, Group group, String query, String itTestMethod,
                            boolean knownFailure, Predicate<Customer> mustMatch,
                            Predicate<Customer> mayMatch) {

    /** Canonical = one capability each; robustness = phrasing, language and one hostile query. */
    public enum Group {
        CANONICAL, ROBUSTNESS
    }

    static BenchmarkCase exact(String id, Group group, String query, String itTestMethod,
                               Predicate<Customer> matches) {
        return new BenchmarkCase(id, group, query, itTestMethod, false, matches, matches);
    }

    /** A case with more than one correct answer: at least {@code mustMatch}, at most {@code mayMatch}. */
    static BenchmarkCase between(String id, Group group, String query, String itTestMethod,
                                 Predicate<Customer> mustMatch, Predicate<Customer> mayMatch) {
        return new BenchmarkCase(id, group, query, itTestMethod, false, mustMatch, mayMatch);
    }

    /** Not expected to hold reliably — measured on purpose, never skipped. */
    static BenchmarkCase knownFailure(String id, Group group, String query, String itTestMethod,
                                      Predicate<Customer> matches) {
        return new BenchmarkCase(id, group, query, itTestMethod, true, matches, matches);
    }

    /** The prompt as it reaches the model, with the empty and blank queries made visible. */
    public String displayQuery() {
        return query.isEmpty() ? "(empty string)" : query.isBlank() ? "(a single blank)" : query;
    }
}
