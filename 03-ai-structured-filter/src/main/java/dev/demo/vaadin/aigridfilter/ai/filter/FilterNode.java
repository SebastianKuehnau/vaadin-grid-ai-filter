package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A node in a {@link CustomerFilter}'s filter tree. Four variants:
 * <ul>
 *   <li>{@link Condition} — a leaf: compare one field with a value.</li>
 *   <li>{@link And} — all {@code children} must match.</li>
 *   <li>{@link Or} — at least one of {@code children} must match.</li>
 *   <li>{@link Not} — negates its single {@code child}.</li>
 * </ul>
 * Children of {@code And}/{@code Or} can themselves be any node, so trees nest arbitrarily, e.g.
 * {@code (city=Berlin OR city=Hamburg) AND (annualRevenue>=500000 OR creditRating=GOOD)}:
 * <pre>{@code
 * {
 *   "type": "AND",
 *   "children": [
 *     { "type": "OR", "children": [
 *         { "type": "CONDITION", "field": "city", "operator": "CONTAINS", "value": "Berlin" },
 *         { "type": "CONDITION", "field": "city", "operator": "CONTAINS", "value": "Hamburg" } ] },
 *     { "type": "OR", "children": [
 *         { "type": "CONDITION", "field": "annualRevenue", "operator": "GREATER_OR_EQUAL", "value": "500000" },
 *         { "type": "CONDITION", "field": "creditRating", "operator": "EQUALS", "value": "GOOD" } ] }
 *   ]
 * }
 * }</pre>
 * An empty {@code children} list on {@code And}/{@code Or} matches everything (see
 * {@link CustomerFilterSpecifications}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FilterNode.Condition.class, name = "CONDITION"),
        @JsonSubTypes.Type(value = FilterNode.And.class, name = "AND"),
        @JsonSubTypes.Type(value = FilterNode.Or.class, name = "OR"),
        @JsonSubTypes.Type(value = FilterNode.Not.class, name = "NOT"),
})
public sealed interface FilterNode {

    /** A leaf condition: compare one customer {@code field} with a {@code value} using {@code operator}. */
    @JsonTypeName("CONDITION")
    @JsonClassDescription("A single condition comparing one field with a value.")
    record Condition(
            @JsonPropertyDescription("the customer field to filter on; one of: companyName, contactName, email, phone, annualRevenue, creditRating, customerSince, lastOrderDate, country, city, postalCode, street, houseNumber, state, countryCode")
            String field,
            @JsonPropertyDescription("how to compare the field with the value")
            Operator operator,
            @JsonPropertyDescription("the value to compare against, as text (ISO date yyyy-MM-dd for date fields, a plain number for annualRevenue, one of GOOD/MEDIUM/POOR for creditRating)")
            String value) implements FilterNode {
    }

    /** All {@code children} must match. */
    @JsonTypeName("AND")
    @JsonClassDescription("All child nodes must match.")
    record And(
            @JsonPropertyDescription("the nodes that must all match; an empty list matches everything")
            List<FilterNode> children) implements FilterNode {
    }

    /** At least one of {@code children} must match. */
    @JsonTypeName("OR")
    @JsonClassDescription("At least one child node must match.")
    record Or(
            @JsonPropertyDescription("the nodes of which at least one must match; an empty list matches everything")
            List<FilterNode> children) implements FilterNode {
    }

    /** Negates {@code child}. */
    @JsonTypeName("NOT")
    @JsonClassDescription("Negates the child node.")
    record Not(
            @JsonPropertyDescription("the node to negate")
            FilterNode child) implements FilterNode {
    }
}
