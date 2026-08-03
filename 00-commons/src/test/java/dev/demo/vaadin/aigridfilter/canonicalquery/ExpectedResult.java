package dev.demo.vaadin.aigridfilter.canonicalquery;

/**
 * Which test result is achievable for a query at all — decided by whether the asking variant's filter type
 * can express it. Read before the assert, and it is what picks the assert.
 * <p>
 * Deliberately <em>not</em> part of {@link CanonicalQuery}: which queries a variant can express is the one
 * thing that genuinely differs per module, and it is the point each AI module demonstrates. Every module
 * therefore states it for itself, in both of its integration tests.
 */
public enum ExpectedResult {

    /** The filter type can express this query; the produced customer set must match an expected one. */
    MATCH,

    /**
     * The filter type cannot express this query, so the produced customer set must <em>differ</em> from the
     * expected one — a documented, non-erroring failure. Should such a case ever match, the test fails: an
     * accidental capability is as much of a finding as a missing one.
     */
    NO_MATCH_BY_DESIGN
}
