package com.veenaacharya.agentic.orchestrator.workflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Loads and validates the stage graph at startup, so an invalid graph stops the application. */
@Configuration
public class WorkflowConfig {

    @Bean
    public WorkflowDefinition workflowDefinition(@Value("${orchestrator.workflow:workflow.yml}") String resource) {
        WorkflowDefinition workflow = new WorkflowLoader().loadFromClasspath(resource);
        new WorkflowValidator().validate(workflow);
        return workflow;
    }
}
