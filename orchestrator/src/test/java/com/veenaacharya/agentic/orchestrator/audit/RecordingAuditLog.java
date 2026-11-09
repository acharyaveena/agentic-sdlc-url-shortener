package com.veenaacharya.agentic.orchestrator.audit;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps audit events in memory so tests can assert on them. */
public final class RecordingAuditLog implements AuditLog {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public List<String> names() {
        return events.stream().map(AuditEvent::event).toList();
    }

    public List<AuditEvent> named(String event) {
        return events.stream().filter(e -> e.event().equals(event)).toList();
    }
}
