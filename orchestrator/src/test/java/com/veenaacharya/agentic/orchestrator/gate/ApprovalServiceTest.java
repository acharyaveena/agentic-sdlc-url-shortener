package com.veenaacharya.agentic.orchestrator.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.veenaacharya.agentic.orchestrator.agent.AgentProperties;
import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalRepository;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageExecutionRepository;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class ApprovalServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final AgentProperties MOCK = new AgentProperties("mock", null, null, 0);

    @Autowired
    private ApprovalRepository approvalRepository;

    @Autowired
    private StageExecutionRepository stageRepository;

    private ApprovalService service(boolean auto, AgentProperties agent) {
        return new ApprovalService(approvalRepository, stageRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                new ApprovalProperties(auto), agent);
    }

    private StageExecution awaitingStage(String runId, String stageId) {
        StageExecution stage = new StageExecution(runId, stageId);
        stage.start(NOW);
        stage.awaitApproval("output");
        return stageRepository.save(stage);
    }

    @Test
    void approveRecordsDecisionAndCompletesStage() {
        ApprovalService service = service(false, MOCK);
        StageExecution stage = awaitingStage("run-1", "design");
        service.request(stage, "design-and-schema");

        Approval approval = service.approve("run-1", "design", "alice", "looks right");

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approval.getDecidedBy()).isEqualTo("alice");
        assertThat(approval.getReason()).isEqualTo("looks right");
        assertThat(approval.getDecidedAt()).isEqualTo(NOW);
        assertThat(stageRepository.findById(stage.getId()).orElseThrow().getStatus())
                .isEqualTo(StageStatus.SUCCEEDED);
    }

    @Test
    void rejectFailsStageWithReason() {
        ApprovalService service = service(false, MOCK);
        StageExecution stage = awaitingStage("run-1", "design");
        service.request(stage, "design-and-schema");

        service.reject("run-1", "design", "bob", "adds unapproved table");

        StageExecution saved = stageRepository.findById(stage.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(StageStatus.FAILED);
        assertThat(saved.getError()).isEqualTo("rejected by bob: adds unapproved table");
        assertThat(service.find("run-1", "design").orElseThrow().getStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    @Test
    void decidingTwiceOrWithoutRequestOrWithoutActorIsRefused() {
        ApprovalService service = service(false, MOCK);
        StageExecution stage = awaitingStage("run-1", "design");
        service.request(stage, "design-and-schema");

        assertThatThrownBy(() -> service.approve("run-1", "design", " ", ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("approval needs an actor");
        service.approve("run-1", "design", "alice", "");
        assertThatThrownBy(() -> service.reject("run-1", "design", "bob", "late"))
                .isInstanceOf(IllegalStateException.class).hasMessage("approval for stage design is already APPROVED");
        assertThatThrownBy(() -> service.approve("run-1", "code", "alice", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no approval requested for stage code in run run-1");
    }

    @Test
    void forRunListsApprovalsInRequestOrder() {
        ApprovalService service = service(false, MOCK);
        service.request(awaitingStage("run-2", "requirements"), "requirements-signoff");
        service.request(awaitingStage("run-2", "design"), "design-and-schema");
        service.request(awaitingStage("run-3", "design"), "design-and-schema");

        assertThat(service.forRun("run-2")).extracting(Approval::getStageId).containsExactly("requirements", "design");
    }

    @Test
    void autoApproveNeverCoversRelease() {
        ApprovalService auto = service(true, MOCK);
        ApprovalService manual = service(false, MOCK);

        assertThat(auto.autoApproves("design-and-schema")).isTrue();
        assertThat(auto.autoApproves(ApprovalService.KIND_RELEASE)).isFalse();
        assertThat(manual.autoApproves("design-and-schema")).isFalse();
    }

    @Test
    void autoApproveIsRefusedOutsideMockMode() {
        assertThatThrownBy(() -> service(true, new AgentProperties("copilot", null, null, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("orchestrator.approval.auto-approve is only allowed with orchestrator.agent.mode=mock");
        assertThat(service(false, new AgentProperties("copilot", null, null, 0))).isNotNull();
    }
}
