package dev.demo.vaadin.aigridfilter.ai.operator;

/** What 02(b) can say about one field: one value, one {@link Operator}, one {@code negate} flag. */
public record FieldCriterion<T>(T value, Operator operator, boolean negate) {

    /** Builds a criterion, or {@code null} if there is nothing to filter on. */
    static <T> FieldCriterion<T> of(T value, Operator operator, Boolean negate) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return null;
        }
        return new FieldCriterion<>(value, operator == null ? Operator.CONTAINS : operator,
                Boolean.TRUE.equals(negate));
    }

    /** How 02(b) compares a field with its value. Negation is not an operator - that is {@link #negate()}. */
    public enum Operator {
        CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH
    }
}
