package com.veenaacharya.agentic.orchestrator.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowLoader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class StageGatesTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final WorkflowDefinition WORKFLOW = new WorkflowLoader().loadFromClasspath("workflow.yml");

    private final ApprovalService approvals = Mockito.mock(ApprovalService.class);
    private final StageGates gates = new StageGates(WORKFLOW, approvals);

    @Test
    void exitGatePassesWhenEveryHeadingIsPresentRegardlessOfLevelAndCase() {
        StageDefinition stage = new StageDefinition("s", List.of(), null, List.of("Findings", "Verdict"), null);

        GateResult result = gates.checkExit(stage, "# findings\ntext\n### VERDICT:\nPASS");

        assertThat(result.passed()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void exitGateListsMissingSections() {
        StageDefinition stage = new StageDefinition("s", List.of(), null, List.of("Findings", "Verdict"), null);

        GateResult result = gates.checkExit(stage, "## Findings\nok\nThe Verdict is PASS in prose only");

        assertThat(result.passed()).isFalse();
        assertThat(result.summary()).isEqualTo("missing required sections: [Verdict]");
    }

    @Test
    void exitGateRejectsHeadingsThatOnlyStartWithTheSectionName() {
        StageDefinition stage = new StageDefinition("s", List.of(), null, List.of("Risks"), null);

        assertThat(gates.checkExit(stage, "## Risks and trade-offs\nx").passed()).isFalse();
    }

    @Test
    void exitGateWithNoRequirementsAlwaysPassesAndNullOutputFailsWhenRequired() {
        assertThat(gates.checkExit(new StageDefinition("s", List.of(), null), null).passed()).isTrue();
        StageDefinition required = new StageDefinition("s", List.of(), null, List.of("A"), null);
        assertThat(gates.checkExit(required, null).passed()).isFalse();
    }

    @Test
    void entryGateFailsWhenUpstreamIsNotComplete() {
        Map<String, StageExecution> executions = executions();

        GateResult result = gates.checkEntry("impact-analysis", executions);

        assertThat(result.passed()).isFalse();
        assertThat(result.summary()).isEqualTo("upstream stage requirements is PENDING");
    }

    @Test
    void entryGateRequiresApprovedRecordForUpstreamSignOffs() {
        Map<String, StageExecution> executions = executions();
        StageExecution requirements = executions.get("requirements");
        requirements.start(NOW);
        requirements.awaitApproval("out");
        requirements.approve(NOW);
        Approval pending = new Approval("run", "requirements", "requirements-signoff", NOW);
        Mockito.when(approvals.find("run", "requirements")).thenReturn(Optional.of(pending));

        assertThat(gates.checkEntry("impact-analysis", executions).summary())
                .isEqualTo("no approved sign-off for upstream stage requirements");

        pending.approve("alice", "", NOW);
        assertThat(gates.checkEntry("impact-analysis", executions).passed()).isTrue();
    }

    @Test
    void entryGateIgnoresSkippedUpstreamStages() {
        Map<String, StageExecution> executions = executions();
        StageExecution requirements = executions.get("requirements");
        requirements.skip(NOW);
        StageExecution impact = executions.get("impact-analysis");
        impact.skip(NOW);

        assertThat(gates.checkEntry("design", executions).passed()).isTrue();
    }

    @Test
    void gateResultCopiesReasons() {
        assertThat(GateResult.pass().passed()).isTrue();
        assertThat(GateResult.fail(List.of("a", "b")).summary()).isEqualTo("a; b");
    }

    private static Map<String, StageExecution> executions() {
        Map<String, StageExecution> executions = new LinkedHashMap<>();
        WORKFLOW.stages().forEach(s -> executions.put(s.id(), new StageExecution("run", s.id())));
        return executions;
    }
}
