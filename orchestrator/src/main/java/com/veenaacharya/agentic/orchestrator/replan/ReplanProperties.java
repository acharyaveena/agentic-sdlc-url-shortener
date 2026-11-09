package com.veenaacharya.agentic.orchestrator.replan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param maxRevisions how many times one stage may be produced in a run before a new run is needed */
@ConfigurationProperties("orchestrator.replan")
public record ReplanProperties(int maxRevisions) {

    public ReplanProperties {
        maxRevisions = maxRevisions <= 0 ? 3 : maxRevisions;
    }
}
