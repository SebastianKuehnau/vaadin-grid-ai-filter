package dev.demo.vaadin.aigridfilter.ai;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/** Logs which test is running and how it ended, so an IT log reads as a list of queries. */
public class TestNameLoggingExtension implements BeforeTestExecutionCallback, TestWatcher {

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        logger(context).info("--> {}", context.getDisplayName());
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        logger(context).info("OK  {}", context.getDisplayName());
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        logger(context).error("FAIL {} - {}", context.getDisplayName(), cause.getMessage());
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        logger(context).info("SKIP {} - {}", context.getDisplayName(), reason.orElse("no reason given"));
    }

    private static Logger logger(ExtensionContext context) {
        return LoggerFactory.getLogger(context.getRequiredTestClass());
    }
}
