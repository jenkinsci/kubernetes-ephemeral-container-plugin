package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.FormValidation;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils;
import org.csanchez.jenkins.plugins.kubernetes.model.TemplateEnvVar;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/**
 * Pipeline step that runs in an ephemeral container of the current Kubernetes Pod agent.
 * @see EphemeralContainerStepExecution
 */
public class EphemeralContainerStep extends Step implements Serializable {

    @Serial
    private static final long serialVersionUID = 5588861066775717487L;

    private final List<TemplateEnvVar> envVars = new ArrayList<>();
    private final String image;
    private boolean alwaysPullImage = true;
    private String targetContainer;
    private String shell;

    @CheckForNull
    private List<String> command;

    @CheckForNull
    private String runAsUser;

    @CheckForNull
    private String runAsGroup;

    @DataBoundConstructor
    public EphemeralContainerStep(String image) {
        if (!PodTemplateUtils.validateImage(image)) {
            throw new IllegalArgumentException("Invalid container image name: '" + image + "'");
        }

        this.image = image;
    }

    public String getImage() {
        return this.image;
    }

    @NonNull
    public List<TemplateEnvVar> getEnvVars() {
        return envVars;
    }

    @DataBoundSetter
    public void setEnvVars(List<TemplateEnvVar> envVars) {
        if (envVars != null) {
            this.envVars.clear();
            this.envVars.addAll(envVars);
        }
    }

    public String getShell() {
        return shell;
    }

    @DataBoundSetter
    public void setShell(String shell) {
        this.shell = Util.fixEmpty(shell);
    }

    public String getTargetContainer() {
        return targetContainer;
    }

    @DataBoundSetter
    public void setTargetContainer(String targetContainer) {
        this.targetContainer = Util.fixEmpty(targetContainer);
    }

    public boolean isAlwaysPullImage() {
        return alwaysPullImage;
    }

    @DataBoundSetter
    public void setAlwaysPullImage(boolean alwaysPullImage) {
        this.alwaysPullImage = alwaysPullImage;
    }

    @CheckForNull
    public String getRunAsUser() {
        return runAsUser;
    }

    @CheckForNull
    public Long getRunAsUserLong() {
        return parseLong(runAsUser);
    }

    @DataBoundSetter
    public void setRunAsUser(@CheckForNull String runAsUser) {
        this.runAsUser = Util.fixEmpty(runAsUser);
    }

    @CheckForNull
    public String getRunAsGroup() {
        return runAsGroup;
    }

    @CheckForNull
    public Long getRunAsGroupLong() {
        return parseLong(runAsGroup);
    }

    @DataBoundSetter
    public void setRunAsGroup(@CheckForNull String runAsGroup) {
        this.runAsGroup = Util.fixEmpty(runAsGroup);
    }

    @CheckForNull
    private static Long parseLong(@CheckForNull String value) {
        value = Util.fixEmptyAndTrim(value);
        try {
            return value == null ? null : NumberUtils.createLong(value);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    @CheckForNull
    public List<String> getCommand() {
        return command;
    }

    @DataBoundSetter
    public void setCommand(@CheckForNull List<String> command) {
        this.command = command;
    }

    /**
     * Use {@link #setCommand(List)} for precise command construction. This setter is used by the snippet
     * generator.
     * @param command full command line
     */
    @SuppressWarnings("unused") // Used by jelly
    @DataBoundSetter
    @Deprecated
    public void setCommandLine(@CheckForNull String command) {
        setCommand(PodTemplateUtils.splitCommandLine(command));
    }

    /**
     * This returns the {@link #getCommand()} joined with a space. It will not precisely round trip with
     * {@link #setCommandLine(String)} for example if one of the arguments contains a space.
     * @return the command args joined by a space
     */
    @SuppressWarnings("unused") // Used by jelly
    @CheckForNull
    public String getCommandLine() {
        return this.command == null ? null : StringUtils.join(this.command, " ");
    }

    @Override
    public StepExecution start(StepContext context) {
        return new EphemeralContainerStepExecution(this, context);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withEphemeralContainer";
        }

        @Override
        @NonNull
        public String getDisplayName() {
            return "Define an Ephemeral Container to add to the current Pod";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Node.class, FilePath.class, Run.class, Launcher.class, TaskListener.class);
        }

        @SuppressWarnings({"unused", "lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"
        }) // Used by jelly, no side effects
        public FormValidation doCheckImage(@QueryParameter String value) {
            if (StringUtils.isEmpty(value)) {
                return FormValidation.error("Image is mandatory");
            } else if (PodTemplateUtils.validateImage(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Malformed image");
            }
        }

        @SuppressWarnings({"unused", "lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"
        }) // Used by jelly, no side effects
        public FormValidation doCheckTargetContainer(@QueryParameter String value) {
            if (PodTemplateUtils.validateContainerName(value)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Invalid container name");
            }
        }

        @SuppressWarnings({"unused", "lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"
        }) // Used by jelly, no side effects
        public FormValidation doCheckRunAsUser(@QueryParameter String value) {
            return validRunAsId(value);
        }

        @SuppressWarnings({"unused", "lgtm[jenkins/csrf]", "lgtm[jenkins/no-permission-check]"
        }) // Used by jelly, no side effects
        public FormValidation doCheckRunAsGroup(@QueryParameter String value) {
            return validRunAsId(value);
        }

        private static FormValidation validRunAsId(String value) {
            value = Util.fixEmpty(value);
            if (value == null) {
                return FormValidation.ok();
            }

            try {
                long v = Long.parseLong(value);
                if (v < 0) {
                    return FormValidation.error("Must be greater than 0");
                }

                return FormValidation.ok();
            } catch (NumberFormatException ignore) {
                return FormValidation.error("Invalid number");
            }
        }
    }

    public static class DeprecatedEphemeralContainerStep extends EphemeralContainerStep {

        @Serial
        private static final long serialVersionUID = -373177686635087038L;

        @DataBoundConstructor
        public DeprecatedEphemeralContainerStep(String image) {
            super(image);
        }

        /**
         * Alternate function name that is hidden. This is here to satisfy the original implementation
         * of this plugin that followed the function naming convention as the kubernetes-plugin
         * (i.e. {@code container}). Users should prefer the more canonical {@code withEphemeralContainer}
         * function name.
         */
        @Extension
        public static class DescriptorImpl extends EphemeralContainerStep.DescriptorImpl {

            @Override
            public String getFunctionName() {
                return "ephemeralContainer";
            }

            @Override
            public boolean isAdvanced() {
                return true;
            }
        }
    }
}
