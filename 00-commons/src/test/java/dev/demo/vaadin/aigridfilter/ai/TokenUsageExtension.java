package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Resets the {@link TokenUsageAdvisor} before an IT class and logs its totals afterwards. */
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
