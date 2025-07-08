package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang.builder.ToStringStyle;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloudTrait;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloudTraitDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Ephemeral container Kubernetes Cloud configuration trait. If the cloud has
 * this trait the ephemeral container step will be allowed to execute.
 * @see EphemeralContainerStepRule
 */
public class EphemeralContainerKubernetesCloudTrait extends KubernetesCloudTrait {

    @NonNull
    private List<EphemeralContainerStepRule> containerStepRules = new ArrayList<>();

    @DataBoundConstructor
    public EphemeralContainerKubernetesCloudTrait() {}

    @NonNull
    public List<EphemeralContainerStepRule> getContainerStepRules() {
        return containerStepRules;
    }

    @DataBoundSetter
    public void setContainerStepRules(List<EphemeralContainerStepRule> containerStepRules) {
        this.containerStepRules =
                containerStepRules != null ? new ArrayList<>(containerStepRules) : Collections.emptyList();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
                .append("containerStepRules", containerStepRules)
                .toString();
    }

    @Extension
    public static class DescriptorImpl extends KubernetesCloudTraitDescriptor {

        @Override
        @NonNull
        public String getDisplayName() {
            return "Ephemeral Containers";
        }

        @Override
        public Optional<KubernetesCloudTrait> getDefaultTrait() {
            return Optional.of(new EphemeralContainerKubernetesCloudTrait());
        }
    }
}
