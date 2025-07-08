package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.*;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EphemeralPodContainerSourceTest {

    @Test
    void getContainerWorkingDir() {
        Pod pod = new PodBuilder()
                .withNewSpec()
                .addNewContainer()
                .withName("foo")
                .withWorkingDir("/app/foo")
                .endContainer()
                .addNewEphemeralContainer()
                .withName("bar")
                .withWorkingDir("/app/bar")
                .endEphemeralContainer()
                .endSpec()
                .build();

        EphemeralPodContainerSource source = new EphemeralPodContainerSource();
        // non-ephemeral containers
        assertTrue(source.getContainerWorkingDir(pod, "foo").isEmpty(), "should only look at ephemeral containers");

        // lookup ephemeral container
        Optional<String> dir = source.getContainerWorkingDir(pod, "bar");
        assertAll(
                "find ephemeral container",
                () -> assertTrue(dir.isPresent(), "should find ephemeral container"),
                () -> assertEquals("/app/bar", dir.orElse(null)));

        // no ephemeral containers
        Optional<String> dir2 = source.getContainerWorkingDir(
                new PodBuilder().withNewSpec().endSpec().build(), "foo");
        assertTrue(dir2.isEmpty(), "no ephemeral containers in pod");
    }

    @Test
    void getContainerStatus() {
        Pod pod = new PodBuilder()
                .withNewStatus()
                .addNewContainerStatus()
                .withName("foo")
                .withNewState()
                .withNewRunning()
                .endRunning()
                .endState()
                .endContainerStatus()
                .addNewEphemeralContainerStatus()
                .withName("bar")
                .withNewState()
                .withNewTerminated()
                .endTerminated()
                .endState()
                .endEphemeralContainerStatus()
                .endStatus()
                .build();

        EphemeralPodContainerSource source = new EphemeralPodContainerSource();
        // non-ephemeral container statuses
        assertTrue(source.getContainerStatus(pod, "foo").isEmpty(), "should only look at ephemeral containers");

        // lookup ephemeral container
        Optional<ContainerStatus> status = source.getContainerStatus(pod, "bar");
        assertAll(
                "find ephemeral container status",
                () -> assertTrue(status.isPresent(), "should find ephemeral container"),
                () -> assertNotNull(
                        status.map(ContainerStatus::getState).map(ContainerState::getTerminated),
                        "expected terminated state"));

        // pod with no ephemeral container statuses
        assertTrue(
                source.getContainerStatus(
                                new PodBuilder().withNewSpec().endSpec().build(), "bar")
                        .isEmpty(),
                "no container statuses");
    }
}
