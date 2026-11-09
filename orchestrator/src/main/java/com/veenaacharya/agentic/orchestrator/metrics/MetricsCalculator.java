package com.veenaacharya.agentic.orchestrator.metrics;

import com.veenaacharya.agentic.orchestrator.state.Approval;
import com.veenaacharya.agentic.orchestrator.state.ApprovalStatus;
import com.veenaacharya.agentic.orchestrator.state.RunStatus;
import com.veenaacharya.agentic.orchestrator.state.StageAttempt;
import com.veenaacharya.agentic.orchestrator.state.StageExecution;
import com.veenaacharya.agentic.orchestrator.state.WorkflowRun;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Pure calculation of {@link MetricsReport} from persisted run state; no I/O, so it is easy to test. */
public final class MetricsCalculator {

    private MetricsCalculator() {
    }

    public static MetricsReport compute(String scope, List<WorkflowRun> runs, List<StageExecution> stages,
                                        List<StageAttempt> attempts, List<Approval> approvals) {
        int succeeded = count(runs, RunStatus.SUCCEEDED);
        int failed = count(runs, RunStatus.FAILED);
        int safeStopped = count(runs, RunStatus.SAFE_STOPPED);

        Map<String, List<StageAttempt>> stageRuns = attempts.stream()
                .sorted(Comparator.comparing(StageAttempt::getAttemptNumber))
                .collect(Collectors.groupingBy(a -> a.getRunId() + "|" + a.getStageId() + "|" + a.getRevision(),
                        LinkedHashMap::new, Collectors.toList()));
        int retried = 0;
        int retries = 0;
        int fallbacks = 0;
        List<Duration> recoveryTimes = new ArrayList<>();
        Map<String, List<Duration>> latencyByStage = new LinkedHashMap<>();
        for (List<StageAttempt> group : stageRuns.values()) {
            StageAttempt first = group.getFirst();
            StageAttempt last = group.getLast();
            if (group.size() > 1) {
                retried++;
                retries += group.size() - 1;
            }
            fallbacks += (int) group.stream()
                    .filter(a -> a.getModel() != null && !Objects.equals(a.getModel(), first.getModel())).count();
            Optional<StageAttempt> firstFailure = group.stream().filter(a -> !a.isSucceeded()).findFirst();
            if (firstFailure.isPresent() && last.isSucceeded()) {
                recoveryTimes.add(Duration.between(firstFailure.get().getFinishedAt(), last.getFinishedAt()));
            }
            latencyByStage.computeIfAbsent(first.getStageId(), k -> new ArrayList<>())
                    .add(Duration.between(first.getStartedAt(), last.getFinishedAt()));
        }

        List<Duration> endToEnd = runs.stream()
                .filter(WorkflowRun::isFinished)
                .filter(r -> r.getFinishedAt() != null)
                .map(r -> Duration.between(r.getCreatedAt(), r.getFinishedAt()))
                .toList();
        List<Approval> decided = approvals.stream()
                .filter(a -> a.getStatus() == ApprovalStatus.APPROVED || a.getStatus() == ApprovalStatus.REJECTED)
                .toList();
        Map<String, Duration> stageLatency = new LinkedHashMap<>();
        latencyByStage.forEach((stage, samples) -> stageLatency.put(stage, average(samples)));

        return new MetricsReport(scope,
                runs.size(), succeeded, failed, safeStopped, runs.size() - succeeded - failed - safeStopped,
                stageRuns.size(), retried, retries, fallbacks,
                recoveryTimes.size(), average(recoveryTimes),
                (int) runs.stream().filter(r -> r.getRollbackTarget() != null).count(),
                average(endToEnd), endToEnd.stream().max(Comparator.naturalOrder()).orElse(null),
                stageLatency,
                decided.size(),
                (int) decided.stream().filter(a -> a.getStatus() == ApprovalStatus.REJECTED).count(),
                average(decided.stream().map(a -> Duration.between(a.getRequestedAt(), a.getDecidedAt())).toList()),
                stages.stream().mapToInt(s -> s.getRevision() - 1).sum());
    }

    private static int count(List<WorkflowRun> runs, RunStatus status) {
        return (int) runs.stream().filter(r -> r.getStatus() == status).count();
    }

    private static Duration average(List<Duration> durations) {
        if (durations.isEmpty()) {
            return null;
        }
        return durations.stream().reduce(Duration.ZERO, Duration::plus).dividedBy(durations.size());
    }
}
