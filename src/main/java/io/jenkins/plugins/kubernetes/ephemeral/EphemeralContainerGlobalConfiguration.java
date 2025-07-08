package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Global configuration that applies to all Kubernetes Clouds.
 * @see EphemeralContainerStepRule
 */
@Extension
@Symbol("ephemeralContainers")
public class EphemeralContainerGlobalConfiguration extends GlobalConfiguration {

    @NonNull
    private List<EphemeralContainerStepRule> containerStepRules = new ArrayList<>();

    @NonNull
    public List<EphemeralContainerStepRule> getContainerStepRules() {
        return containerStepRules;
    }

    @DataBoundSetter
    public void setContainerStepRules(List<EphemeralContainerStepRule> containerStepRules) {
        this.containerStepRules =
                containerStepRules != null ? new ArrayList<>(containerStepRules) : Collections.emptyList();
    }

    public static EphemeralContainerGlobalConfiguration get() {
        return ExtensionList.lookupSingleton(EphemeralContainerGlobalConfiguration.class);
    }
}
