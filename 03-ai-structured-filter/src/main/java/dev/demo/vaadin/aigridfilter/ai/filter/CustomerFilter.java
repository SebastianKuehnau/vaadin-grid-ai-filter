package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A customer filter: a flat list of {@link Condition}s, all combined with AND. Deliberately flat
 * (no AND/OR/NOT tree) — trades away cross-field OR and arbitrary nesting for a shape that's far
 * easier for a small/local model to produce correctly. Multiple values for the same field (OR)
 * and negation are still expressible per-{@link Condition}; see there.
 * <p>
 * An empty (or {@code null}) {@code conditions} list matches every customer.
 */
@JsonClassDescription("A customer filter: a flat list of conditions, ALL combined with AND. Empty list matches all.")
public record CustomerFilter(
        @JsonPropertyDescription("all conditions the customer must satisfy (AND); empty list matches everything")
        List<Condition> conditions) {
}
