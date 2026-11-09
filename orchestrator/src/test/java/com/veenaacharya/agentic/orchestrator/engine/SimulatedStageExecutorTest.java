package com.veenaacharya.agentic.orchestrator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.veenaacharya.agentic.orchestrator.workflow.ChangeType;
import com.veenaacharya.agentic.orchestrator.workflow.StageDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SimulatedStageExecutorTest {

    @Test
    void returnsSuccessDescribingStageInputsAndRequest() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("requirements", "r");
        upstream.put("design", "d");
        StageContext context = new StageContext("run-1",
                new StageDefinition("code", List.of("design"), Set.of()),
                ChangeType.GREENFIELD, "shorten urls", upstream);

        StageResult result = new SimulatedStageExecutor().execute(context);

        assertThat(result.succeeded()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.output())
                .isEqualTo("[simulated] code | inputs=[requirements, design] | request=shorten urls");
    }

    @Test
    void stageResultFactoriesSetFields() {
        assertThat(StageResult.success("o")).isEqualTo(new StageResult(true, "o", null));
        assertThat(StageResult.failure("e")).isEqualTo(new StageResult(false, null, "e"));
    }
}
