package com.veenaacharya.agentic.orchestrator.gate;

import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.StageStatus;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import com.veenaacharya.agentic.orchestrator.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Entry and exit gates evaluated by the engine around every stage. */
@Component
public class StageGates {

    private final WorkflowDefinition workflow;
    private final ApprovalService approvals;

    public StageGates(WorkflowDefinition workflow, ApprovalService approvals) {
        this.workflow = workflow;
        this.approvals = approvals;
    }

    /**
     * Entry gate: every upstream stage is complete, and each upstream stage that needs sign-off
     * has an APPROVED record, checked against the approval table and not just the stage status.
     */
    public GateResult checkEntry(String stageId, Map<String, StageExecution> executions) {
        List<String> failures = new ArrayList<>();
        for (String ancestorId : workflow.ancestorsOf(stageId)) {
            StageExecution ancestor = executions.get(ancestorId);
            if (ancestor.getStatus() == StageStatus.SKIPPED) {
                continue;
            }
            if (!ancestor.isSatisfied()) {
                failures.add("upstream stage " + ancestorId + " is " + ancestor.getStatus());
                continue;
            }
            if (workflow.stage(ancestorId).approval() != null) {
                Optional<Approval> approval = approvals.find(ancestor.getRunId(), ancestorId);
                if (approval.isEmpty() || approval.get().getStatus() != ApprovalStatus.APPROVED) {
                    failures.add("no approved sign-off for upstream stage " + ancestorId);
                }
            }
        }
        return failures.isEmpty() ? GateResult.pass() : GateResult.fail(failures);
    }

    /** Exit gate: the output contains every required markdown heading. */
    public GateResult checkExit(StageDefinition stage, String output) {
        List<String> missing = new ArrayList<>();
        for (String section : stage.requiredSections()) {
            Pattern heading = Pattern.compile(
                    "^#{1,6}\\s*" + Pattern.quote(section) + "\\s*:?\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            if (output == null || !heading.matcher(output).find()) {
                missing.add(section);
            }
        }
        return missing.isEmpty()
                ? GateResult.pass()
                : GateResult.fail(List.of("missing required sections: " + missing));
    }
}
