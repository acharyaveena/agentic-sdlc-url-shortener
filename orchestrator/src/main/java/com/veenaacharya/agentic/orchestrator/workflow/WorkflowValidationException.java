package com.veenaacharya.agentic.orchestrator.workflow;

/** Thrown when a workflow cannot be loaded or is not a valid DAG. */
public class WorkflowValidationException extends RuntimeException {

    public WorkflowValidationException(String message) {
        super(message);
    }

    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
