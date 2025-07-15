package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.List;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.model.KeyValueEnvVar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class KubernetesClientModelFactoryTest {

    @Test
    void buildEphemeralContainer() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");

        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals("foo", ec.getName());
        assertEquals("maven", ec.getImage());
        assertEquals("Always", ec.getImagePullPolicy());
        assertEquals(List.of(), ec.getCommand());
        assertEquals(
                List.of(
                        "sh",
                        "-c",
                        "set -e; { while ! test -f '/tmp/foo-jenkins-step-is-done-monitor' ; do sleep 1; done }"),
                ec.getArgs());
        assertEquals(true, ec.getTty());
        assertEquals(true, ec.getStdin());
        assertEquals(List.of(), ec.getEnv());
    }

    @Test
    void buildEphemeralContainerCommand() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setCommand(List.of("sh", "/opt/entrypoint.sh"));
        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals(List.of("sh", "/opt/entrypoint.sh"), ec.getCommand());
        assertEquals(
                List.of(
                        "sh",
                        "-c",
                        "set -e; { while ! test -f '/tmp/foo-jenkins-step-is-done-monitor' ; do sleep 1; done }"),
                ec.getArgs());
    }

    @Test
    void buildEphemeralContainerCommandEmpty() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setCommand(List.of());
        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals(
                List.of(
                        "sh",
                        "-c",
                        "set -e; { while ! test -f '/tmp/foo-jenkins-step-is-done-monitor' ; do sleep 1; done }"),
                ec.getCommand());
        assertEquals(List.of(), ec.getArgs());
    }

    @Test
    void buildEphemeralContainerEnvVar() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setEnvVars(List.of(new KeyValueEnvVar("bar", "baz")));

        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals(List.of(new EnvVar("bar", "baz", null)), ec.getEnv());
    }

    @Test
    void buildEphemeralContainerImagePullPolicy() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setAlwaysPullImage(false);

        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals("IfNotPresent", ec.getImagePullPolicy());
    }

    @Test
    void buildEphemeralContainerWorkingDir() {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");

        VolumeMount vm = new VolumeMountBuilder()
                .withName("workspace")
                .withMountPath("/jenkins/work")
                .build();

        Pod p = new PodBuilder()
                .withNewSpec()
                .addNewContainer()
                .withName(KubernetesCloud.JNLP_NAME)
                .withWorkingDir("/jenkins/work")
                .addToVolumeMounts(vm)
                .endContainer()
                .endSpec()
                .build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        assertEquals("/jenkins/work", ec.getWorkingDir());
        assertEquals(List.of(vm), ec.getVolumeMounts());
    }

    @ParameterizedTest
    @CsvSource({
        " , , false",
        "1, , true",
        " , 1, true",
        "1, 1, true",
    })
    void buildSecurityContext(String user, String group, boolean expect) {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setRunAsUser(user);
        step.setRunAsGroup(group);

        Pod p = new PodBuilder().withNewSpec().endSpec().build();

        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer("foo", step, p);
        SecurityContext sc = ec.getSecurityContext();
        if (expect) {
            assertNotNull(sc, "expected security context configured");
            assertEquals(user == null ? null : Long.parseLong(user), sc.getRunAsUser());
            assertEquals(group == null ? null : Long.parseLong(group), sc.getRunAsGroup());
        } else {
            assertNull(sc, "expect security context not configured");
        }
    }
}
