package dev.demo.vaadin.aigridfilter.ai.filter;

import java.math.BigDecimal;

/**
 * One {@code annualRevenue} bound to match, as extracted from a natural-language query. Either
 * bound may be {@code null} for an open-ended range ("over 500000" -> {@code atLeast} only, "under
 * 50000" -> {@code atMost} only); both set means "between". Multiple ranges for the same query are
 * combined with OR by {@link CustomerSpecifications#from}, mirroring how multiple dates are each
 * turned into a year range and OR-combined.
 * <p>
 * Named {@code atLeast}/{@code atMost} rather than {@code min}/{@code max}: with a small local
 * model driving the tool call, a self-describing field name carries the "over" vs. "under"
 * direction on its own, rather than relying entirely on the tool description prose to map it onto
 * an abstract bound.
 */
public record RevenueRange(BigDecimal atLeast, BigDecimal atMost) {
}
