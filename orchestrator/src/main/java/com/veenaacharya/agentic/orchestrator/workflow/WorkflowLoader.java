package com.veenaacharya.agentic.orchestrator.workflow;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;

/** Parses a workflow definition from YAML. Unknown keys are rejected so typos fail fast. */
public class WorkflowLoader {

    private final ObjectMapper mapper = YAMLMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    public WorkflowDefinition load(InputStream yaml) {
        try {
            return mapper.readValue(yaml, WorkflowDefinition.class);
        } catch (IOException e) {
            throw new WorkflowValidationException("cannot parse workflow: " + e.getMessage(), e);
        }
    }

    public WorkflowDefinition loadFromClasspath(String resource) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new WorkflowValidationException("workflow resource not found: " + resource);
            }
            return load(in);
        } catch (IOException e) {
            throw new WorkflowValidationException("cannot read workflow resource: " + resource, e);
        }
    }
}
