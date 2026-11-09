package com.veenaacharya.agentic.orchestrator.metrics;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Reliability metrics for one run or for all runs.
 *
 * @param stageRuns        one per stage revision that ran at least one attempt
 * @param retries          attempts beyond the first, across all stage runs
 * @param fallbackAttempts attempts made on a different model than the stage's first attempt
 * @param recoveries       stage runs that failed at least once and then succeeded
 * @param mttr             mean time from a stage's first failure to its successful attempt
 * @param stageReruns      extra stage revisions caused by re-planning or stale outputs
 */
public record MetricsReport(String scope,
                            int runs, int succeeded, int failed, int safeStopped, int inProgress,
                            int stageRuns, int retriedStageRuns, int retries, int fallbackAttempts,
                            int recoveries, Duration mttr,
                            int rollbacks,
                            Duration averageEndToEnd, Duration maxEndToEnd,
                            Map<String, Duration> averageStageLatency,
                            int approvalsDecided, int rejections, Duration averageApprovalWait,
                            int stageReruns) {

    public MetricsReport {
        averageStageLatency = Map.copyOf(averageStageLatency);
    }

    public int finished() {
        return succeeded + failed + safeStopped;
    }

    /** Succeeded share of finished runs; null when no run has finished. */
    public Double successRate() {
        return ratio(succeeded, finished());
    }

    public Double retryRate() {
        return ratio(retriedStageRuns, stageRuns);
    }

    public Double rollbackRate() {
        return ratio(rollbacks, finished());
    }

    public String toMarkdown() {
        StringBuilder md = new StringBuilder("## Metrics (" + scope + ")\n\n| metric | value |\n|---|---|\n");
        row(md, "Runs", runs + " (succeeded " + succeeded + ", failed " + failed + ", safe-stopped " + safeStopped
                + ", in progress " + inProgress + ")");
        row(md, "Success rate", percent(successRate()) + " (" + succeeded + " of " + finished() + " finished runs)");
        row(md, "Retry rate", percent(retryRate()) + " of stage runs (" + retriedStageRuns + " of " + stageRuns + ")");
        row(md, "Retries", retries + " attempts beyond the first, " + fallbackAttempts + " on a fallback model");
        row(md, "Rollback rate", percent(rollbackRate()) + " (" + rollbacks + " of " + finished() + " finished runs)");
        row(md, "MTTR", format(mttr) + " over " + recoveries + " recover" + (recoveries == 1 ? "y" : "ies"));
        row(md, "End-to-end latency", "avg " + format(averageEndToEnd) + ", max " + format(maxEndToEnd)
                + " (includes time waiting for approvals)");
        row(md, "Approval wait", "avg " + format(averageApprovalWait) + " over " + approvalsDecided + " decisions ("
                + rejections + " rejected)");
        row(md, "Re-runs (re-plan or stale)", String.valueOf(stageReruns));
        if (!averageStageLatency.isEmpty()) {
            md.append("\n### Stage latency (first attempt start to last attempt end, average)\n\n")
                    .append("| stage | latency |\n|---|---|\n");
            averageStageLatency.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> row(md, e.getKey(), format(e.getValue())));
        }
        return md.toString();
    }

    static String format(Duration duration) {
        if (duration == null) {
            return "n/a";
        }
        long seconds = duration.getSeconds();
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.1f s", duration.toMillis() / 1000.0);
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    private static String percent(Double ratio) {
        return ratio == null ? "n/a" : String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }

    private static Double ratio(int part, int whole) {
        return whole == 0 ? null : (double) part / whole;
    }

    private static void row(StringBuilder md, String name, String value) {
        md.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }
}
