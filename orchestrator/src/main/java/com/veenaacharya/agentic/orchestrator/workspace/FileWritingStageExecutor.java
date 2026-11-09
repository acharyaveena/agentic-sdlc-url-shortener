package com.veenaacharya.agentic.orchestrator.workspace;

import com.veenaacharya.agentic.orchestrator.agent.AgentStageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.policy.FileChange;
import com.veenaacharya.agentic.orchestrator.policy.PolicyContext;
import com.veenaacharya.agentic.orchestrator.policy.PolicyGuard;
import com.veenaacharya.agentic.orchestrator.policy.Violation;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Asks the agent for files, checks them against policy, and writes them only if every rule passes. */
@Component
public class FileWritingStageExecutor implements StageExecutor {

    private final AgentStageExecutor agent;
    private final PolicyGuard guard;
    private final WorkspaceWriter writer;
    private final FileChangeParser parser = new FileChangeParser();

    public FileWritingStageExecutor(AgentStageExecutor agent,
                                    @Qualifier("writeGuard") PolicyGuard guard,
                                    WorkspaceWriter writer) {
        this.agent = agent;
        this.guard = guard;
        this.writer = writer;
    }

    @Override
    public StageResult execute(StageContext context) {
        StageResult generated = agent.execute(context);
        if (!generated.succeeded()) {
            return generated;
        }
        List<FileChange> changes;
        try {
            changes = parser.parse(generated.output());
        } catch (FileChangeParseException e) {
            return StageResult.failure("cannot read agent output: " + e.getMessage());
        }
        if (changes.isEmpty()) {
            return StageResult.failure("agent output contains no FILE: blocks");
        }
        List<Violation> violations = guard.check(changes,
                new PolicyContext(context.stage().id(), context.upstreamOutputs()));
        if (!violations.isEmpty()) {
            return StageResult.failure("policy violations, nothing was written:\n"
                    + violations.stream().map(Violation::toString).collect(Collectors.joining("\n")));
        }
        try {
            return StageResult.success(generated.output(), writer.write(changes));
        } catch (SecurityException | UncheckedIOException e) {
            return StageResult.failure("cannot write files: " + e.getMessage());
        }
    }
}
