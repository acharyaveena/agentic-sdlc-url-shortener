package com.veenaacharya.agentic.orchestrator.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param dir directory holding one {@code <run id>.jsonl} file per run */
@ConfigurationProperties("orchestrator.audit")
public record AuditProperties(String dir) {

    public AuditProperties {
        dir = dir == null || dir.isBlank() ? "runs/audit" : dir;
    }
}
