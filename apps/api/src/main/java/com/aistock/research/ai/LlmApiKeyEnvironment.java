package com.aistock.research.ai;

import org.springframework.stereotype.Component;

@Component
public class LlmApiKeyEnvironment {

    public String value(String name) {
        return System.getenv(name);
    }
}
