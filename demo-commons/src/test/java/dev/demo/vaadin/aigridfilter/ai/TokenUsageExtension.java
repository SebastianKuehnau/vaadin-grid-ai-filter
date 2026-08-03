package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Drives the {@link TokenUsageAdvisor} around an IT class: zeroed before the first query, summarised after
 * the last. Add it with {@code @ExtendWith(TokenUsageExtension.class)} and the test class itself says
 * nothing about tokens.
 * <p>
 * <b>An extension rather than a base class, because there are two inheritance lines.</b>
 * {@code AbstractCustomerListViewBrowserlessIT} already extends {@code SpringBrowserlessTest}, so a shared
 * superclass could only have served the service-level {@code AbstractCustomerSearchIT} — and the
 * bookkeeping would have stayed duplicated in the one it could not reach. An extension is orthogonal to
 * inheritance and serves both.
 * <p>
 * The bean comes from the Spring test context, which both base classes have: the service-level one through
 * {@code @SpringBootTest}, the browserless one through {@code SpringBrowserlessTest}, which carries
 * {@code @ExtendWith(SpringExtension.class)} itself. {@code getApplicationContext} creates the
 * {@code TestContextManager} on demand, so this does not depend on which extension runs first.
 * <p>
 * <b>Why {@link TokenUsageAdvisor#reset()} per class and not once per run:</b> Failsafe reuses its JVM fork
 * and Spring caches the application context, so several IT classes can share one advisor instance. Without
 * the reset, each class would report the totals of every class before it.
 */
public class TokenUsageExtension implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        advisor(context).reset();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        advisor(context).logSummary(context.getRequiredTestClass().getSimpleName());
    }

    private static TokenUsageAdvisor advisor(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context).getBean(TokenUsageAdvisor.class);
    }
}
