package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A flat filter: just a list of {@link FilterCriterion}. There is no explicit AND/OR switch — how
 * the criteria combine is fixed and resolved in {@link CustomerFilterSpecifications}: criteria on the
 * same field are OR-alternatives, different fields are AND-combined. This keeps the structure simple
 * enough for smaller, local LLMs to produce reliably as structured output.
 */
@JsonClassDescription("A flat customer filter: a list of conditions. Same-field conditions are OR'd, different fields are AND'd.")
public record CustomerFilter(
        @JsonPropertyDescription("the conditions to apply; use an empty list to show all customers")
        List<FilterCriterion> criteria) {
}
