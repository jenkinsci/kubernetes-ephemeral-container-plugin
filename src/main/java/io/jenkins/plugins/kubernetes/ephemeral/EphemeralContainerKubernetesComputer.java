package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.io.IOException;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesComputer;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;

/**
 * Extension of {@link KubernetesComputer} that adds build environment variables.
 */
public class EphemeralContainerKubernetesComputer extends KubernetesComputer {

    public EphemeralContainerKubernetesComputer(KubernetesSlave slave) {
        super(slave);
    }

    @NonNull
    @Override
    public EnvVars buildEnvironment(@NonNull TaskListener listener) throws IOException, InterruptedException {
        EnvVars envVars = super.buildEnvironment(listener);
        KubernetesSlave slave = getNode();
        if (slave != null) {
            KubernetesCloud cloud = slave.getKubernetesCloud();
            boolean hasTrait =
                    cloud.getTrait(EphemeralContainerKubernetesCloudTrait.class).isPresent();
            envVars.put("KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED", Boolean.toString(hasTrait));
        }

        return envVars;
    }
}
