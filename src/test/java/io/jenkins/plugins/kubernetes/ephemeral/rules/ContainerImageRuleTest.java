package io.jenkins.plugins.kubernetes.ephemeral.rules;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStep;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepRule;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContainerImageRuleTest {

    @ParameterizedTest
    @CsvSource({
        "'',''",
        "*/maven, ^(.*\\Q/maven\\E)$",
        "docker.io/*, ^(\\Qdocker.io/\\E.*)$",
        "docker.io/*/java, ^(\\Qdocker.io/\\E.*\\Q/java\\E)$",
        "'*/maven \n# allow bitnami\ndocker.io/bitnami/*\n   \ncgr.dev/*/node', ^(.*\\Q/maven\\E|\\Qdocker.io/bitnami/\\E.*|\\Qcgr.dev/\\E.*\\Q/node\\E)$"
    })
    void wildcardPatternsToRegex(String input, String expected) {
        assertEquals(expected, ContainerImageRule.wildcardPatternsToRegex(input));
    }

    @ParameterizedTest
    @CsvSource({
        "'', ALLOW, docker.io/maven:latest, REJECT",
        "'', REJECT, docker.io/maven:latest, ",
        "*, ALLOW, docker.io/maven:latest, ALLOW",
        "*/maven, ALLOW, docker.io/maven:latest, ALLOW",
        "*/maven, ALLOW, docker.io/maven:latest@sha256:12345679, ALLOW",
        "*/maven, ALLOW, docker.io/node, REJECT",
        "*/maven, REJECT, docker.io/maven, REJECT",
        "*/maven, REJECT, docker.io/node, ",
        "'*/maven\n*/node\n*/rust', ALLOW, docker.io/node:22, ALLOW",
        // invalid image ref
        "*/maven, ALLOW, $foo, REJECT",
        "*/maven, REJECT, $foo, REJECT"
    })
    void test(
            String names,
            EphemeralContainerStepRule.Action action,
            String image,
            EphemeralContainerStepRule.Action expected) {
        EphemeralContainerStep step = new EphemeralContainerStep(image);
        ContainerImageRule rule = new ContainerImageRule(names, action);
        Optional<EphemeralContainerStepRule.Result> result = rule.test(step);
        if (expected == null) {
            assertTrue(result.isEmpty());
        } else {
            assertTrue(result.isPresent());
            assertEquals(expected, result.get().getAction());
        }
    }

    @Test
    void constructors() {
        assertAll("default constructor", () -> {
            ContainerImageRule dft = new ContainerImageRule();
            assertAll(
                    () -> assertEquals("*", dft.getNames()),
                    () -> assertEquals(EphemeralContainerStepRule.Action.ALLOW, dft.getAction()));
        });
    }
}
