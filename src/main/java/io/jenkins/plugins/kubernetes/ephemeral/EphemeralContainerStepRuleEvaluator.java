package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import java.util.Optional;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Rule engine for evaluating {@link EphemeralContainerStepRule}'s.
 */
@Restricted(NoExternalUse.class)
class EphemeralContainerStepRuleEvaluator {

    private static final Logger LOGGER = Logger.getLogger(EphemeralContainerStepRuleEvaluator.class.getName());

    /**
     * Evaluate {@link EphemeralContainerStepRule}'s against the given step before it is added to
     * the Pod. If rules reject the step configuration an {@link AbortException} is thrown.
     * @param step ephemeral container step to evaluate
     * @param rules list of rules to check
     * @throws AbortException if step rejected by the supplied rules
     */
    public void eval(
            @NonNull EphemeralContainerStep step, @NonNull Iterable<? extends EphemeralContainerStepRule> rules)
            throws AbortException {
        for (EphemeralContainerStepRule rule : rules) {
            Optional<EphemeralContainerStepRule.Result> action = rule.test(step);
            if (action.isPresent()) {
                EphemeralContainerStepRule.Result result = action.get();
                if (result.getAction() == EphemeralContainerStepRule.Action.REJECT) {
                    LOGGER.info(() -> "Ephemeral container step rejected, reason="
                            + result.getReason().orElse("none") + ", step=" + step + ", rule=" + rule);
                    throw new AbortException(
                            result.getReason().orElse("Ephemeral container step rejected due to " + rule));
                }
            }
        }
    }
}
