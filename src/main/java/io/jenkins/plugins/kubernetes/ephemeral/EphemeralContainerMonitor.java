package io.jenkins.plugins.kubernetes.ephemeral;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Factory for container monitoring commands.
 */
@Restricted(NoExternalUse.class)
class EphemeralContainerMonitor {

    private static final int FILE_MONITOR_POLL_SECS =
            Integer.getInteger(EphemeralContainerMonitor.class.getName() + ".fileMonitorPollSecs", 1);

    /**
     * Container command used to keep the container running until execution block
     * is finished.
     * @see io.fabric8.kubernetes.api.model.EphemeralContainer#setCommand(List)
     * @param containerName container name
     * @return container wait command
     */
    static String[] containerWaitCommand(String containerName) {
        return new String[] {"sh", "-c", fileMonitorScript(containerName)};
    }

    /**
     * Command to execute in the container to trigger the wait command to exit.
     * @param containerName container name
     * @return container stop command
     */
    static String[] containerStopCommand(String containerName) {
        return new String[] {"touch", fileMonitorPath(containerName)};
    }

    private static String fileMonitorScript(@NonNull String containerName) {
        int pollSecs = Math.max(1, FILE_MONITOR_POLL_SECS);
        String fileMonPath = fileMonitorPath(containerName);
        return "set -e; { while ! test -f '" + fileMonPath + "' ; do sleep " + pollSecs + "; done }";
    }

    private static String fileMonitorPath(String containerName) {
        return "/tmp/" + containerName + "-jenkins-step-is-done-monitor";
    }
}
