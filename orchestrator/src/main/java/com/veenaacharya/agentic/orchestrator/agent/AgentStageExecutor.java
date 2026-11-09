package com.veenaacharya.agentic.orchestrator.agent;

import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import org.springframework.stereotype.Component;

/** Runs a stage by asking the configured agent runtime; model errors become stage failures. */
@Component
public class AgentStageExecutor implements StageExecutor {

    private final AgentRuntime runtime;
    private final PromptBuilder prompts;

    public AgentStageExecutor(AgentRuntime runtime, AgentProperties properties) {
        this.runtime = runtime;
        this.prompts = new PromptBuilder(properties);
    }

    @Override
    public StageResult execute(StageContext context) {
        try {
            return StageResult.success(runtime.complete(prompts.build(context)));
        } catch (AgentException e) {
            return StageResult.failure(e.getMessage());
        }
    }
}
