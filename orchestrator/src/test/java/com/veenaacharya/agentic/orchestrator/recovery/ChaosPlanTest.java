package com.veenaacharya.agentic.orchestrator.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChaosPlanTest {

    @Test
    void parsesCountsAndAlwaysAcrossSeveralStages() {
        ChaosPlan plan = ChaosPlan.parse(" design:2 , build-test:ALWAYS ");

        assertThat(plan.failures()).containsExactly(Map.entry("design", 2), Map.entry("build-test", -1));
        assertThat(plan.stageIds()).containsExactly("design", "build-test");
        assertThat(plan.spec()).isEqualTo("design:2,build-test:always");
        assertThat(plan.isActive()).isTrue();
    }

    @Test
    void countFailsOnlyThatManyFirstAttempts() {
        ChaosPlan plan = ChaosPlan.parse("design:2");

        assertThat(plan.shouldFail("design", 1)).isTrue();
        assertThat(plan.shouldFail("design", 2)).isTrue();
        assertThat(plan.shouldFail("design", 3)).isFalse();
        assertThat(plan.shouldFail("code", 1)).isFalse();
    }

    @Test
    void alwaysFailsEveryAttempt() {
        ChaosPlan plan = ChaosPlan.parse("build-test:always");

        assertThat(plan.shouldFail("build-test", 1)).isTrue();
        assertThat(plan.shouldFail("build-test", 99)).isTrue();
    }

    @Test
    void blankOrNullMeansNoChaos() {
        assertThat(ChaosPlan.parse(null)).isSameAs(ChaosPlan.NONE);
        assertThat(ChaosPlan.parse("  ")).isSameAs(ChaosPlan.NONE);
        assertThat(ChaosPlan.NONE.isActive()).isFalse();
        assertThat(ChaosPlan.NONE.spec()).isEmpty();
        assertThat(ChaosPlan.NONE.shouldFail("design", 1)).isFalse();
    }

    @Test
    void malformedEntriesAreRejectedWithTheExpectedSyntax() {
        for (String bad : new String[] {"design", "design:", ":2", "design:0", "design:-1", "design:two", "a:1:2"}) {
            assertThatThrownBy(() -> ChaosPlan.parse(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expected <stage>:<count|always>");
        }
        assertThatThrownBy(() -> ChaosPlan.parse("design:1,code:x"))
                .hasMessage("invalid chaos entry 'code:x'; expected <stage>:<count|always>, count at least 1");
    }

    @Test
    void sameStageTwiceIsRejected() {
        assertThatThrownBy(() -> ChaosPlan.parse("design:1,design:2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("chaos names stage 'design' twice");
    }
}
