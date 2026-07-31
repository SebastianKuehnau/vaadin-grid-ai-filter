package dev.demo.vaadin.aigridfilter.canonicalquery;

/**
 * Whether a variant's filter type can express a query at all.
 * <p>
 * Deliberately <em>not</em> part of {@link CanonicalQuery}: which queries a variant can express is the one
 * thing that genuinely differs per module, and it is the point each AI module demonstrates. Every module
 * therefore keeps its own query-to-outcome mapping.
 */
public enum Outcome {

    /** The filter type can express this query; the produced customer set must match an expected one. */
    SUCCESS,

    /**
     * The filter type cannot express this query. The test asserts that the produced customer set
     * <em>differs</em> from the expected one — a documented, non-erroring failure. Should such a case ever
     * match, the test fails: an accidental capability is as much of a finding as a missing one.
     */
    FAIL_BY_DESIGN
}
