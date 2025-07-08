package io.jenkins.plugins.kubernetes.ephemeral;

import hudson.Extension;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesComputer;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesComputerFactory;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;

/**
 * {@link KubernetesComputerFactory} implementation that always creates instances of
 * {@link EphemeralContainerKubernetesComputer}.
 */
@Extension
public class EphemeralContainerKubernetesComputerFactory extends KubernetesComputerFactory {

    @Override
    public KubernetesComputer newInstance(KubernetesSlave slave) {
        return new EphemeralContainerKubernetesComputer(slave);
    }
}
