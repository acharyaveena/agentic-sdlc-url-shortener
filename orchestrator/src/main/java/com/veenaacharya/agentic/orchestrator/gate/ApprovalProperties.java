package com.veenaacharya.agentic.orchestrator.gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param autoApprove approve automatically (as actor "auto"); allowed only with the mock agent and never for release */
@ConfigurationProperties("orchestrator.approval")
public record ApprovalProperties(boolean autoApprove) {
}
