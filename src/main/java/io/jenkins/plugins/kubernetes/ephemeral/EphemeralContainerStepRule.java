package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;
import java.util.Optional;

/**
 * Ephemeral container step rule. Rules are evaluated before the step is executed and may cause the step
 * to be aborted before the container is added to the Pod.
 */
public abstract class EphemeralContainerStepRule extends AbstractDescribableImpl<EphemeralContainerStepRule>
        implements ExtensionPoint {

    /**
     * Evaluate rule for the current step.s
     * @param step ephemeral container step
     * @return rule result
     */
    @NonNull
    public abstract Optional<Result> test(@NonNull EphemeralContainerStep step);

    /**
     * {@link EphemeralContainerStepRule} result action.
     */
    public enum Action {
        /**
         * Allow step to proceed.
         */
        ALLOW("Allow"),
        /**
         * Reject container step. Trigger {@link hudson.AbortException}.
         */
        REJECT("Reject");

        private final String description;

        Action(String description) {
            this.description = description;
        }

        /**
         * Get readable description of the action.
         * @return action description
         */
        public String getDescription() {
            return description;
        }
    }

    /**
     * {@link EphemeralContainerStepRule} result.
     */
    public static class Result {
        private final Action action;
        private final String reason;

        /**
         * Create a {@link Action#REJECT} action result with optional reason.
         * @param reason reason for rejection
         * @return new result
         */
        @NonNull
        public static Result reject(@Nullable String reason) {
            return new Result(Action.REJECT, reason);
        }

        /**
         * Create result action without a reason.
         * @param action action
         */
        public Result(@NonNull Action action) {
            this(action, null);
        }

        /**
         * Create result with action and reason.
         * @param action action
         * @param reason reason or {@code null}
         */
        public Result(@NonNull Action action, @Nullable String reason) {
            this.action = action;
            this.reason = reason;
        }

        /**
         * Rule requested action.
         * @return action
         */
        @NonNull
        public Action getAction() {
            return this.action;
        }

        /**
         * Reason for the action selected.
         * @return reason or empty
         */
        @NonNull
        public Optional<String> getReason() {
            return Optional.ofNullable(reason);
        }
    }
}
