package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A customer filter: a tree of {@link FilterNode}s, combining conditions with AND/OR/NOT to
 * arbitrary depth. This makes any boolean combination expressible, including cross-field OR
 * (e.g. {@code city=Berlin OR annualRevenue>=1000000}), which a flat list of conditions cannot
 * represent. See {@link FilterNode} for the tree shape and a worked JSON example.
 * <p>
 * A {@code null} root, or an AND/OR node with no children, matches every customer.
 */
@JsonClassDescription("A customer filter: a tree of AND/OR/NOT/condition nodes. A null root matches all customers.")
public record CustomerFilter(
        @JsonPropertyDescription("the root node of the filter tree; omit or use an AND with no children to show all customers")
        FilterNode root) {
}
