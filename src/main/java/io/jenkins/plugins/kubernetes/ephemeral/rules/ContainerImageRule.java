package io.jenkins.plugins.kubernetes.ephemeral.rules;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStep;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepRule;
import io.jenkins.plugins.kubernetes.ephemeral.ImageReference;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * {@link EphemeralContainerStepRule} that validates the step image. This rule is configured with a set of image
 * name patterns that are applied against a normalized image reference. This rule can either allow or rejects
 * matched images.
 * <p>
 * Patterns can use the wildcard character "{@code *}" to match any character sequences. Patterns are separated
 * by newlines. Lines that start with "{@code #}" will be ignored.
 * <p>
 * If configured to {@link EphemeralContainerStepRule.Action#ALLOW}, if none of the supplied patterns match
 * the step will be rejected.
 * <p>
 * If configured to {@link EphemeralContainerStepRule.Action#REJECT}, if any of the supplied patterns match
 * the step will be rejected.
 *
 * @see ImageReference
 * @see EphemeralContainerStep
 */
public class ContainerImageRule extends EphemeralContainerStepRule {

    private final String names;
    private final Action action;
    private transient Pattern regex;

    /**
     * Default rule that allows all images.
     */
    public ContainerImageRule() {
        this("*", Action.ALLOW);
    }

    /**
     * Create new rules with image name patterns and action to perform if one of the patterns
     * match.
     * @param names newline separated image name patterns
     * @param action action to perform on match, defaults to {@link EphemeralContainerStepRule.Action#ALLOW} if {@code null}
     */
    @DataBoundConstructor
    public ContainerImageRule(String names, Action action) {
        this.names = StringUtils.trimToEmpty(names);
        this.action = action == null ? Action.ALLOW : action;
    }

    @NonNull
    private Pattern getNamesRegex() {
        if (regex == null) {
            regex = Pattern.compile(wildcardPatternsToRegex(names));
        }

        return regex;
    }

    static String wildcardPatternsToRegex(@NonNull String patterns) {
        String p = patterns.lines()
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .filter(s -> !s.startsWith("#"))
                .map(ContainerImageRule::wildcardToRegex)
                .collect(Collectors.joining("|"));

        return p.isBlank() ? "" : "^(" + p + ")$";
    }

    private static String wildcardToRegex(String pattern) {
        String[] list = StringUtils.splitPreserveAllTokens(pattern, "*");
        for (int i = 0; i < list.length; i++) {
            if (!Strings.CS.equals(list[i], "")) {
                list[i] = Pattern.quote(list[i]);
            }
        }

        return String.join(".*", list);
    }

    @Override
    @NonNull
    public Optional<Result> test(@NonNull EphemeralContainerStep step) {
        Optional<ImageReference> reference = ImageReference.parse(step.getImage());
        if (reference.isEmpty()) {
            return Optional.of(Result.reject("Invalid image reference"));
        }

        Predicate<String> matcher = getNamesRegex().asMatchPredicate();
        if (matcher.test(reference.get().getName())) {
            if (action == Action.REJECT) {
                return Optional.of(Result.reject(
                        "Image '" + step.getImage() + "' has been disallowed by Jenkins administrators."));
            }

            return Optional.of(new Result(action));
        } else if (action == Action.ALLOW) {
            return Optional.of(Result.reject("Image '" + step.getImage() + "' not in allow list"));
        }

        return Optional.empty();
    }

    /**
     * Get image name patterns to match.
     * @return newline separated image name patterns
     */
    @NonNull
    public String getNames() {
        return names;
    }

    /**
     * Action to perform when image pattern matches.
     * @return action
     */
    @NonNull
    public Action getAction() {
        return action;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SIMPLE_STYLE)
                .append("action", action)
                .append("names", names)
                .toString();
    }

    @Extension
    @Symbol("containerImageRule")
    public static class DescriptorImpl extends Descriptor<EphemeralContainerStepRule> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Container Image Rule";
        }
    }
}
