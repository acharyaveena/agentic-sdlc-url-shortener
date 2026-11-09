package com.veenaacharya.agentic.orchestrator.policy;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param allowedGroupIds Maven groupIds a generated pom.xml may use */
@ConfigurationProperties("orchestrator.policy")
public record PolicyProperties(List<String> allowedGroupIds) {

    public PolicyProperties {
        allowedGroupIds = allowedGroupIds == null || allowedGroupIds.isEmpty()
                ? List.of("org.springframework.boot", "org.springframework", "com.h2database",
                "org.hibernate.validator", "jakarta.validation", "org.junit.jupiter", "org.mockito",
                "org.assertj", "org.apache.maven.plugins")
                : List.copyOf(allowedGroupIds);
    }
}
