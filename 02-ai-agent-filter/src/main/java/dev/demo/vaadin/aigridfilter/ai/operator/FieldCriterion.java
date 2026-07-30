package dev.demo.vaadin.aigridfilter.ai.operator;

/**
 * What variant 02(b) can say about <em>one</em> customer field: a single {@code value}, a single
 * {@link Operator}, and a single {@code negate} flag. Purely the internal grouping of the three flat
 * tool parameters the model fills per field ({@code city}, {@code cityOperator}, {@code cityNegate});
 * it is never itself a tool parameter.
 * <p>
 * The deliberate ceiling of this variant lives in this record's shape: one value means no OR within a
 * field, and one operator means no range — "between 100000 and 500000" needs a lower <em>and</em> an
 * upper bound on the same field, which cannot be said here. Lifting that ceiling requires several
 * conditions per field, i.e. the condition-list shape of {@code 03-ai-structured-filter} and
 * {@code 04-ai-hybrid-filter}.
 */
public record FieldCriterion<T>(T value, Operator operator, boolean negate) {

    /**
     * Builds a criterion, or {@code null} if there is nothing to filter on (no value, or a blank
     * string). A missing operator defaults to {@link Operator#CONTAINS}, and a missing
     * {@code negate} to {@code false}, so a small model omitting either still produces a usable
     * filter instead of an NPE.
     */
    static <T> FieldCriterion<T> of(T value, Operator operator, Boolean negate) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        return new FieldCriterion<>(value, operator == null ? Operator.CONTAINS : operator,
                Boolean.TRUE.equals(negate));
    }
}
