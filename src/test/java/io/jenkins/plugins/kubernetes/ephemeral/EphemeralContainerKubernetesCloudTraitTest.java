package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.kubernetes.ephemeral.rules.ContainerImageRule;
import java.util.List;
import java.util.Optional;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloudTrait;
import org.junit.jupiter.api.Test;

class EphemeralContainerKubernetesCloudTraitTest {

    @Test
    void containerStepRules() {
        EphemeralContainerKubernetesCloudTrait trait = new EphemeralContainerKubernetesCloudTrait();
        assertAll("default", () -> {
            assertNotNull(trait.getContainerStepRules());
            assertTrue(trait.getContainerStepRules().isEmpty(), "expect empty");
        });

        assertAll("set rules", () -> {
            List<EphemeralContainerStepRule> rules = List.of(new ContainerImageRule());
            trait.setContainerStepRules(rules);
            assertEquals(rules, trait.getContainerStepRules());
            assertNotSame(rules, trait.getContainerStepRules(), "should be a copy");
        });

        assertAll("set null", () -> {
            trait.setContainerStepRules(null);
            assertNotNull(trait.getContainerStepRules());
            assertTrue(trait.getContainerStepRules().isEmpty(), "expect empty");
        });
    }

    @Test
    void testToString() {
        EphemeralContainerKubernetesCloudTrait trait = new EphemeralContainerKubernetesCloudTrait();
        assertEquals("EphemeralContainerKubernetesCloudTrait[containerStepRules=[]]", trait.toString());
    }

    @Test
    void defaultTrait() {
        EphemeralContainerKubernetesCloudTrait.DescriptorImpl descriptor =
                new EphemeralContainerKubernetesCloudTrait.DescriptorImpl();
        Optional<KubernetesCloudTrait> dt = descriptor.getDefaultTrait();
        assertTrue(dt.isPresent());
        assertInstanceOf(EphemeralContainerKubernetesCloudTrait.class, dt.get());
    }
}
