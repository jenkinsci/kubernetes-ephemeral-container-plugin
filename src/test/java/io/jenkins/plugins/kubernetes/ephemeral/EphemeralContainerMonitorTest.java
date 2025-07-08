package io.jenkins.plugins.kubernetes.ephemeral;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EphemeralContainerMonitorTest {

    @Test
    void containerWaitCommand() {
        assertArrayEquals(
                new String[] {
                    "sh",
                    "-c",
                    "set -e; { while ! test -f '/tmp/foobar-jenkins-step-is-done-monitor' ; do sleep 1; done }"
                },
                EphemeralContainerMonitor.containerWaitCommand("foobar"));
    }

    @Test
    void containerStopCommand() {
        assertArrayEquals(
                new String[] {"touch", "/tmp/foobar-jenkins-step-is-done-monitor"},
                EphemeralContainerMonitor.containerStopCommand("foobar"));
    }
}
