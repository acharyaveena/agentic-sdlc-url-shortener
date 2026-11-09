package com.veenaacharya.agentic.orchestrator.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic runtime for tests and offline runs; needs no Copilot login. Answers come from
 * {@link #respondWith}, else from {@code mock/<stage>.md} on the classpath, else a one-line stub.
 */
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
        String canned = cannedOutputs.get(request.stageId());
        if (canned != null) {
            return canned;
        }
        String bundled = bundledOutput(request.stageId());
        return bundled != null
                ? bundled
                : "# " + request.stageId() + " (mock output)\n\nmodel: " + request.model();
    }

    private static String bundledOutput(String stageId) {
        try (InputStream in = MockAgentRuntime.class.getClassLoader()
                .getResourceAsStream("mock/" + stageId + ".md")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException("cannot read mock output for stage " + stageId, e);
        }
    }

    public List<AgentRequest> requests() {
        return List.copyOf(requests);
    }
}
