package dev.demo.vaadin.aigridfilter.ai.filter;

/**
 * How a {@link Condition} compares a customer field with a value. Negation is not an operator —
 * see {@link Condition#negate()}.
 */
public enum Operator {
    CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH
}
