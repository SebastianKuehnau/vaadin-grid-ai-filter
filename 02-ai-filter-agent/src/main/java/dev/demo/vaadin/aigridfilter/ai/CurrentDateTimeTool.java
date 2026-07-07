package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDateTime;

/** Stateless tool-provider POJO letting the model resolve relative dates ("last week", "yesterday"). */
class CurrentDateTimeTool {

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
