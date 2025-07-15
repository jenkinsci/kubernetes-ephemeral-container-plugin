package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.PodContainerSource;

/**
 * Implementation of {@link PodContainerSource} that exclusively supports Ephemeral Containers.
 */
@Extension
public class EphemeralPodContainerSource extends PodContainerSource {

    @Override
    public Optional<String> getContainerWorkingDir(@NonNull Pod pod, @NonNull String containerName) {
        return getEphemeralContainerWorkingDir(pod, containerName);
    }

    @Override
    public Optional<ContainerStatus> getContainerStatus(@NonNull Pod pod, @NonNull String containerName) {
        return getEphemeralContainerStatus(pod, containerName);
    }

    /**
     * Lookup ephemeral container working dir.
     * @param pod pod reference
     * @param containerName container name
     * @return optional container working dir if found
     */
    public static Optional<String> getEphemeralContainerWorkingDir(@NonNull Pod pod, @NonNull String containerName) {
        List<EphemeralContainer> ephemeralContainers = pod.getSpec().getEphemeralContainers();
        if (ephemeralContainers == null) {
            return Optional.empty();
        }

        return ephemeralContainers.stream()
                .filter(c -> StringUtils.equals(c.getName(), containerName))
                .findAny()
                .map(EphemeralContainer::getWorkingDir);
    }

    /**
     * Lookup ephemeral container status. Other container types are not supported by this lookup.
     * @param pod pod reference
     * @param containerName container name
     * @return optional container status if found
     */
    public static Optional<ContainerStatus> getEphemeralContainerStatus(
            @NonNull Pod pod, @NonNull String containerName) {
        PodStatus podStatus = pod.getStatus();
        if (podStatus == null) {
            return Optional.empty();
        }

        return podStatus.getEphemeralContainerStatuses().stream()
                .filter(cs -> StringUtils.equals(cs.getName(), containerName))
                .findFirst();
    }
}
