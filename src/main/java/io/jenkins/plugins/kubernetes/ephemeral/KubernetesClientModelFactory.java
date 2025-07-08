package io.jenkins.plugins.kubernetes.ephemeral;

import static io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerMonitor.containerWaitCommand;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.EphemeralContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.util.List;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.model.TemplateEnvVar;

/**
 * Factory for creating model objects for the Kubernetes client.
 * @see EphemeralContainer
 */
class KubernetesClientModelFactory {

    /**
     * Create an {@link EphemeralContainer} model instance for the target {@link Pod}.
     * @param containerName container name, must be unique within the pod
     * @param step ephemeral container step
     * @param pod target pod resource
     * @return ephemeral container instance, never {@code null}
     */
    @NonNull
    @SuppressFBWarnings(value = "BC_UNCONFIRMED_CAST_OF_RETURN_VALUE", justification = "not applicable")
    static EphemeralContainer createEphemeralContainer(
            @NonNull String containerName, @NonNull EphemeralContainerStep step, @NonNull Pod pod) {
        List<EnvVar> envVars =
                step.getEnvVars().stream().map(TemplateEnvVar::buildEnvVar).toList();

        EphemeralContainerBuilder containerBuilder = new EphemeralContainerBuilder()
                .withName(containerName)
                .withTargetContainerName(step.getTargetContainer())
                .withTty(true)
                .withStdin(true)
                .withImage(step.getImage())
                .withImagePullPolicy(step.isAlwaysPullImage() ? "Always" : "IfNotPresent")
                .withEnv(envVars);

        pod.getSpec().getContainers().stream()
                .filter(c -> KubernetesCloud.JNLP_NAME.equals(c.getName()))
                .findFirst()
                .ifPresent(container -> containerBuilder
                        .withVolumeMounts(container.getVolumeMounts().toArray(new VolumeMount[0]))
                        .withWorkingDir(container.getWorkingDir()));

        if (step.getRunAsUser() != null || step.getRunAsGroup() != null) {
            containerBuilder
                    .withNewSecurityContext()
                    .withRunAsUser(step.getRunAsUserLong())
                    .withRunAsGroup(step.getRunAsGroupLong())
                    .endSecurityContext();
        }

        // Windows containers not yet supported, sorry
        // Our file monitor script that will exit the container when the step ends to return resources to the Pod.
        String[] monitorCmd = containerWaitCommand(containerName);
        List<String> stepCmd = step.getCommand();
        if (stepCmd == null) {
            // Use default container entrypoint. It is assumed to be able to handle taking an executable as the first
            // arg. Specifically "sh" so the file monitory and be run.
            containerBuilder.withArgs(monitorCmd);
        } else if (stepCmd.isEmpty()) {
            // if command is empty array it tells us the user wants us to override the entrypoint
            // the equivalent of `--entrypoint=''` in docker speak.
            containerBuilder.withCommand(monitorCmd);
        } else {
            // Use the user supplied entrypoint. Like the default entrypoint it is assumed to handle taking an
            // executable
            // as the first arg.
            containerBuilder.withCommand(stepCmd);
            containerBuilder.withArgs(monitorCmd);
        }

        return containerBuilder.build();
    }
}
