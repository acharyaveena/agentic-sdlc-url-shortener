package com.veenaacharya.agentic.orchestrator.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageAttemptRepository;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRunRepository;
import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class MetricsServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private WorkflowRunRepository runs;

    @Autowired
    private StageExecutionRepository stages;

    @Autowired
    private StageAttemptRepository attempts;

    @Autowired
    private ApprovalRepository approvals;

    @Test
    void reportsForOneRunOrAcrossAllRuns() {
        WorkflowRun first = new WorkflowRun("a", ChangeType.GREENFIELD, "r", T0);
        first.finish(RunStatus.SUCCEEDED, T0.plusSeconds(10));
        WorkflowRun second = new WorkflowRun("b", ChangeType.GREENFIELD, "r", T0);
        second.finish(RunStatus.FAILED, T0.plusSeconds(20));
        runs.save(first);
        runs.save(second);
        attempts.save(new StageAttempt(first.getId(), "design", 1, "m", false, "x", T0, T0.plusSeconds(1)));
        attempts.save(new StageAttempt(first.getId(), "design", 2, "m", true, null, T0.plusSeconds(1), T0.plusSeconds(3)));
        MetricsService service = new MetricsService(runs, stages, attempts, approvals);

        MetricsReport one = service.forRun(first.getId());
        MetricsReport all = service.forAllRuns();

        assertThat(one.scope()).isEqualTo("run " + first.getId());
        assertThat(one.runs()).isEqualTo(1);
        assertThat(one.retries()).isEqualTo(1);
        assertThat(one.successRate()).isEqualTo(1.0);
        assertThat(all.scope()).isEqualTo("all runs");
        assertThat(all.runs()).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> service.forRun("nope")).hasMessage("unknown run: nope");
    }
}
