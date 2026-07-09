package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.ai.provider", havingValue = "ollama", matchIfMissing = true)
    public ChatModel primaryChatModel(
            @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel ollama
    ) {
        return ollama;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.ai.provider", havingValue = "openai")
    public ChatModel primaryChatModelOpenAi(
            @Autowired(required = false) @Qualifier("openAiChatModel") ChatModel openai
    ) {
        return openai;
    }

}
