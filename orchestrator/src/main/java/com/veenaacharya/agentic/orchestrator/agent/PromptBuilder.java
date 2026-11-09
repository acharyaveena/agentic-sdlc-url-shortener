package com.veenaacharya.agentic.orchestrator.agent;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Builds the model request for a stage: a per-stage system prompt plus the request and approved upstream outputs. */
public class PromptBuilder {

    private final AgentProperties properties;

    public PromptBuilder(AgentProperties properties) {
        this.properties = properties;
    }

    public AgentRequest build(StageContext context) {
        String stageId = context.stage().id();
        return new AgentRequest(context.runId(), stageId, properties.modelFor(stageId),
                loadTemplate(stageId), userPrompt(context));
    }

    static String userPrompt(StageContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## Change request\n").append(context.request()).append("\n\n");
        prompt.append("## Change type\n").append(context.changeType().name().toLowerCase()).append("\n");
        for (Map.Entry<String, String> upstream : context.upstreamOutputs().entrySet()) {
            prompt.append("\n## Output of stage: ").append(upstream.getKey()).append("\n")
                    .append(upstream.getValue()).append("\n");
        }
        return prompt.toString();
    }

    private static String loadTemplate(String stageId) {
        String resource = "prompts/" + stageId + ".md";
        try (InputStream in = PromptBuilder.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new AgentException("no prompt template for stage " + stageId + " (" + resource + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException("cannot read prompt template " + resource, e);
        }
    }
}
