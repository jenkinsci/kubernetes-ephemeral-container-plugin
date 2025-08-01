package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.util.FormValidation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EphemeralContainerStepTest {

    @ParameterizedTest
    @CsvSource({
        ", OK",
        "'', OK",
        "a, ERROR",
        "-1, ERROR",
        "0, OK",
        "1000, OK",
        "2.1, ERROR",
    })
    void testDoCheckRunAsId(String input, FormValidation.Kind expected) {
        EphemeralContainerStep.DescriptorImpl descriptor = new EphemeralContainerStep.DescriptorImpl();
        assertAll(
                () -> assertEquals(expected, descriptor.doCheckRunAsUser(input).kind),
                () -> assertEquals(expected, descriptor.doCheckRunAsGroup(input).kind));
    }

    @ParameterizedTest
    @CsvSource({
        ", ERROR",
        "'', ERROR",
        "foo bar, ERROR",
        "maven, OK",
        "docker.io/library/maven:latest@sha256:5a156ff125e5a12ac7ff43ee5120fa249cf62, OK",
    })
    void testDoCheckImage(String input, FormValidation.Kind expected) {
        EphemeralContainerStep.DescriptorImpl descriptor = new EphemeralContainerStep.DescriptorImpl();
        assertEquals(expected, descriptor.doCheckImage(input).kind);
    }

    @ParameterizedTest
    @CsvSource({
        ", OK",
        "'', OK",
        "foo bar, ERROR",
        "foo/bar, ERROR",
        "foo-bar, OK",
        "maven, OK",
    })
    void testDoCheckTargetContainer(String input, FormValidation.Kind expected) {
        EphemeralContainerStep.DescriptorImpl descriptor = new EphemeralContainerStep.DescriptorImpl();
        assertEquals(expected, descriptor.doCheckTargetContainer(input).kind);
    }

    @ParameterizedTest
    @CsvSource({
        ",",
        "'',",
        "' ',",
        "foo,",
        "1000, 1000",
    })
    void testRunAsUserGroupToLong(String input, Long expected) {
        EphemeralContainerStep step = new EphemeralContainerStep("maven");
        step.setRunAsGroup(input);
        step.setRunAsUser(input);
        assertEquals(expected, step.getRunAsGroupLong());
        assertEquals(expected, step.getRunAsUserLong());
    }
}
