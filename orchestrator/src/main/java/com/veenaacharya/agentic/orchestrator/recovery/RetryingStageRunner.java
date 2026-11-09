package com.veenaacharya.agentic.orchestrator.recovery;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.engine.StageContext;
import com.veenaacharya.agentic.orchestrator.engine.StageExecutor;
import com.veenaacharya.agentic.orchestrator.engine.StageResult;
import com.veenaacharya.agentic.orchestrator.gate.GateResult;
import com.veenaacharya.agentic.orchestrator.gate.StageGates;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Runs one stage with bounded retries: up to {@code maxAttempts} on the stage's model, then one attempt on
 * the fallback model. Each retry is told why the previous attempt failed. Stages that do not call a model
 * (build, policy scan) get a single attempt, because repeating a deterministic check gives the same answer.
 * Runs on worker threads and touches no persistent state.
 */
@Component
public class RetryingStageRunner {

    private static final Set<String> MODEL_KINDS = Set.of("agent", "files");

    private final StageExecutor executor;
    private final StageGates gates;
    private final AgentProperties agent;
    private final RecoveryProperties recovery;
    private final Clock clock;

    public RetryingStageRunner(StageExecutor executor, StageGates gates, AgentProperties agent,
                               RecoveryProperties recovery, Clock clock) {
        this.executor = executor;
        this.gates = gates;
        this.agent = agent;
        this.recovery = recovery;
        this.clock = clock;
    }

    public StageOutcome run(StageContext context, ChaosPlan chaos) {
        StageDefinition stage = context.stage();
        List<String> models = modelsFor(stage);
        List<AttemptRecord> attempts = new ArrayList<>();
        StageResult result = null;
        String feedback = null;
        for (int i = 0; i < models.size(); i++) {
            int number = i + 1;
            Instant started = clock.instant();
            result = attempt(context.forAttempt(models.get(i), feedback), chaos, number);
            attempts.add(new AttemptRecord(number, models.get(i), result.succeeded(), result.error(),
                    started, clock.instant()));
            if (result.succeeded()) {
                break;
            }
            feedback = result.error();
        }
        return new StageOutcome(result, attempts);
    }

    /** The model for each attempt in order; a single null entry for stages that do not call a model. */
    List<String> modelsFor(StageDefinition stage) {
        if (!MODEL_KINDS.contains(stage.kind())) {
            return Collections.singletonList(null);
        }
        String model = agent.modelFor(stage.id());
        List<String> models = new ArrayList<>(Collections.nCopies(recovery.maxAttempts(), model));
        recovery.fallbackFor(model).ifPresent(models::add);
        return models;
    }

    private StageResult attempt(StageContext context, ChaosPlan chaos, int number) {
        if (chaos.shouldFail(context.stage().id(), number)) {
            return StageResult.failure("chaos: injected failure on attempt " + number);
        }
        StageResult result;
        try {
            result = executor.execute(context);
        } catch (RuntimeException e) {
            return StageResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result == null) {
            return StageResult.failure("executor returned no result");
        }
        if (!result.succeeded()) {
            return result;
        }
        GateResult exit = gates.checkExit(context.stage(), result.output());
        return exit.passed() ? result : StageResult.failure("exit gate failed: " + exit.summary());
    }
}
