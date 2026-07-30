package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Span;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;

/**
 * Traffic-light indicator for a customer's creditworthiness: a colored dot
 * followed by the rating label.
 * <p>
 * This component owns the <em>presentation</em> only — it maps the
 * domain-level {@link CreditRating} to a CSS modifier class. The actual colors
 * live in {@code credit-score-indicator.css}, and the credit rule itself lives
 * in {@link CreditRating}, so all three can change independently.
 * <p>
 * Status is never conveyed by color alone: the label is always shown next to
 * the dot, and an {@code aria-label} exposes it to screen readers (WCAG-friendly).
 */
@StyleSheet("credit-score-indicator.css")
public class CreditScoreIndicator extends Span {

    public CreditScoreIndicator(Customer customer) {
        var rating = customer.getCreditRating();
        addClassNames("credit-indicator", modifierClass(rating));

        var dot = new Span();
        dot.addClassName("credit-indicator__dot");
        dot.getElement().setAttribute("aria-hidden", "true");

        add(dot, new Span(rating.getLabel()));
        getElement().setAttribute("aria-label",
                "Credit rating: " + rating.getLabel() + ", score " + customer.getCreditScore());
    }

    /** Maps the domain rating to the CSS modifier class that selects its color. */
    private static String modifierClass(CreditRating rating) {
        return switch (rating) {
            case GOOD -> "credit-indicator--good";
            case MEDIUM -> "credit-indicator--medium";
            case POOR -> "credit-indicator--poor";
        };
    }
}
