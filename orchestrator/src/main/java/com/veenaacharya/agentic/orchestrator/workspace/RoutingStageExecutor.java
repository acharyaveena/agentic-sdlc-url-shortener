package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sends each stage to the executor matching its {@code kind} in workflow.yml. */
@Component
@Primary
public class RoutingStageExecutor implements StageExecutor {

    private final AgentStageExecutor agent;
    private final FileWritingStageExecutor files;
    private final BuildStageExecutor build;
    private final PolicyScanStageExecutor policyScan;

    public RoutingStageExecutor(AgentStageExecutor agent, FileWritingStageExecutor files,
                                BuildStageExecutor build, PolicyScanStageExecutor policyScan) {
        this.agent = agent;
        this.files = files;
        this.build = build;
        this.policyScan = policyScan;
    }

    @Override
    public StageResult execute(StageContext context) {
        return switch (context.stage().kind()) {
            case "files" -> files.execute(context);
            case "build" -> build.execute(context);
            case "policy-scan" -> policyScan.execute(context);
            default -> agent.execute(context);
        };
    }
}
