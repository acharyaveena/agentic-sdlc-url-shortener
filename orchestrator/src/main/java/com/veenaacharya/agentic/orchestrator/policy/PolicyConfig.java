package com.veenaacharya.agentic.orchestrator.policy;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PolicyProperties.class)
public class PolicyConfig {

    /** Preventive: every rule runs on each file before an agent's output is written. */
    @Bean
    public PolicyGuard writeGuard(PolicyProperties properties) {
        return new DefaultPolicyGuard(List.of(
                new WritePathRule(),
                new SecretScanRule(),
                new DependencyAllowlistRule(Set.copyOf(properties.allowedGroupIds())),
                new SchemaChangeRule()));
    }

    /** Detective: state rules re-checked over the whole product tree by the policy-scan stage. */
    @Bean
    public PolicyGuard scanGuard(PolicyProperties properties) {
        return new DefaultPolicyGuard(List.of(
                new SecretScanRule(),
                new DependencyAllowlistRule(Set.copyOf(properties.allowedGroupIds()))));
    }
}
