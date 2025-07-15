package io.jenkins.plugins.kubernetes.ephemeral;

import static io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepRule.Action.ALLOW;
import static io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepRule.Action.REJECT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EphemeralContainerStepRuleEvaluatorTest {

    static Stream<Arguments> evalTestCases() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        return Stream.of(
                arguments(step, rules(ALLOW), false),
                arguments(step, rules(REJECT), true),
                arguments(step, rules(ALLOW, REJECT), true),
                arguments(step, rules(REJECT, ALLOW), true),
                arguments(step, rules((EphemeralContainerStepRule.Action) null), false),
                arguments(step, rules(null, REJECT), true),
                arguments(step, rules(null, ALLOW), false));
    }

    @ParameterizedTest
    @MethodSource("evalTestCases")
    void eval(EphemeralContainerStep step, List<EphemeralContainerStepRule> rules, boolean wantError) {
        EphemeralContainerStepRuleEvaluator evaluator = new EphemeralContainerStepRuleEvaluator();
        if (wantError) {
            assertThrows(AbortException.class, () -> evaluator.eval(step, rules));
        } else {
            assertDoesNotThrow(() -> evaluator.eval(step, rules));
        }
    }

    static EphemeralContainerStepRule rule(EphemeralContainerStepRule.Action action) {
        return new EphemeralContainerStepRule() {
            @NonNull
            @Override
            public Optional<Result> test(@NonNull EphemeralContainerStep step) {
                return action == null ? Optional.empty() : Optional.of(new EphemeralContainerStepRule.Result(action));
            }
        };
    }

    static List<EphemeralContainerStepRule> rules(EphemeralContainerStepRule.Action... action) {
        return Stream.of(action)
                .map(EphemeralContainerStepRuleEvaluatorTest::rule)
                .toList();
    }
}
