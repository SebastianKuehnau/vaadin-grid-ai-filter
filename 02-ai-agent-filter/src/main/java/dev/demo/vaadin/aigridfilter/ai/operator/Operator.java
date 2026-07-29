package dev.demo.vaadin.aigridfilter.ai.operator;

/**
 * How variant 02(b) compares a customer field with the single value given for it. Negation is not an
 * operator — see {@link FieldCriterion#negate()}.
 * <p>
 * A verbatim mirror of {@code 03-ai-structured-filter}'s {@code Operator} (and
 * {@code 04-ai-hybrid-filter}'s copy of it): the modules are separate Spring Boot apps with no shared
 * Maven module, so each keeps its own copy. What differs between them is not the operator vocabulary
 * but where the operator sits — here it is one flat tool parameter per field, there it is part of a
 * {@code Condition} in a condition list.
 */
public enum Operator {
    CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH
}
