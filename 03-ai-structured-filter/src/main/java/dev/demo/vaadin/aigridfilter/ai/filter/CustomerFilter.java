package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** A flat list of {@link Condition}s, all AND-combined - no AND/OR/NOT tree, which a small model gets wrong. */
@JsonClassDescription("A customer filter: a flat list of conditions, ALL combined with AND. Empty list matches all.")
public record CustomerFilter(
        @JsonPropertyDescription("all conditions the customer must satisfy (AND); empty list matches everything")
        List<Condition> conditions) {
}
