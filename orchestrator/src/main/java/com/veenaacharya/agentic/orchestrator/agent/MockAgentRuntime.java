package com.veenaacharya.agentic.orchestrator.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Deterministic runtime for tests and offline runs; needs no Copilot login. */
public class MockAgentRuntime implements AgentRuntime {

    private final List<AgentRequest> requests = new CopyOnWriteArrayList<>();
    private final Set<String> failingStages = ConcurrentHashMap.newKeySet();
    private final Map<String, String> cannedOutputs = new ConcurrentHashMap<>();

    /** Makes every call for this stage throw, to exercise failure handling. */
    public MockAgentRuntime failOn(String stageId) {
        failingStages.add(stageId);
        return this;
    }

    public MockAgentRuntime respondWith(String stageId, String output) {
        cannedOutputs.put(stageId, output);
        return this;
    }

    @Override
    public String complete(AgentRequest request) {
        requests.add(request);
        if (failingStages.contains(request.stageId())) {
            throw new AgentException("mock failure for stage " + request.stageId());
        }
        return cannedOutputs.getOrDefault(request.stageId(),
                "# " + request.stageId() + " (mock output)\n\nmodel: " + request.model());
    }

    public List<AgentRequest> requests() {
        return List.copyOf(requests);
    }
}
