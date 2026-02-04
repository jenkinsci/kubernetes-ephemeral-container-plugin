package io.jenkins.plugins.kubernetes.ephemeral;

import static io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerMonitor.containerStopCommand;
import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.closeQuietly;
import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.closeQuietlyCallback;

import com.codahale.metrics.MetricRegistry;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.console.ModelHyperlinkNote;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.Iterators;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EphemeralContainer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.commons.lang3.time.StopWatch;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodUtils;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.ContainerExecDecorator;
import org.csanchez.jenkins.plugins.kubernetes.pipeline.KubernetesNodeContext;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Ephemeral Container step execution is responsible for creating a new Ephemeral Container
 * to the current Kubernetes agent Pod. A container is added to the Pod with a file monitor
 * command that waits until a file is created to signal the container should exit. This
 * implementation has a number of retry mechanisms to deal with high kubelet load that
 * may cause a context deadline miss or conflict errors patching the Pod itself.
 * @see EphemeralContainerStep
 * @see EphemeralContainerStepRuleEvaluator
 * @see EphemeralContainerExecDecorator
 */
public class EphemeralContainerStepExecution extends GeneralNonBlockingStepExecution {

    @Serial
    private static final long serialVersionUID = 7634132798345235774L;

    private static final Logger LOGGER = Logger.getLogger(EphemeralContainerStepExecution.class.getName());

    /** Max retry attempts if Pod update fails. */
    private static final int PATCH_MAX_RETRY =
            Integer.getInteger(EphemeralContainerStepExecution.class.getName() + ".patchMaxRetry", 10);
    /** Max wait time in seconds between Pod update retries. */
    private static final int PATCH_RETRY_MAX_WAIT =
            Integer.getInteger(EphemeralContainerStepExecution.class.getName() + ".patchRetryMaxWaitSecs", 2);
    /** Max retry attempts to start an ephemeral container. */
    private static final int START_MAX_RETRY =
            Integer.getInteger(EphemeralContainerStepExecution.class.getName() + ".startMaxRetry", 3);
    /** Max wait time in seconds between container start retries. */
    private static final int START_RETRY_MAX_WAIT =
            Integer.getInteger(EphemeralContainerStepExecution.class.getName() + ".startRetryMaxWaitSecs", 2);
    /** Max time in seconds to wait for whoami commands to return. */
    private static final int WHOAMI_TIMEOUT =
            Integer.getInteger(EphemeralContainerStepExecution.class.getName() + ".whoamiTimeoutSecs", 180);

    // Kubernetes state reason codes
    private static final String KUBE_REASON_START_ERROR = "StartError";
    private static final String KUBE_REASON_ERROR = "Error";
    private static final String KUBE_REASON_CONFLICT = "Conflict";
    private static final String KUBE_REASON_CONTAINER_CREATING = "ContainerCreating";
    private static final String KUBE_REASON_POD_INITIALIZING = "PodInitializing";

    /** Set of container start failure state reasons to retry on. */
    private static final Set<String> START_RETRY_REASONS = Collections.singleton(KUBE_REASON_START_ERROR);

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient EphemeralContainerStep step;

    @CheckForNull
    private ContainerExecDecorator decorator;

    EphemeralContainerStepExecution(@NonNull EphemeralContainerStep step, @NonNull StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(getContext());
        KubernetesSlave slave = nodeContext.getKubernetesSlave();
        KubernetesCloud cloud = slave.getKubernetesCloud();
        EphemeralContainerKubernetesCloudTrait trait = cloud.getTrait(EphemeralContainerKubernetesCloudTrait.class)
                .orElseThrow(() -> new AbortException("Ephemeral containers not enabled on " + cloud.getDisplayName()));

        EphemeralContainerGlobalConfiguration globalConfig = EphemeralContainerGlobalConfiguration.get();
        Iterable<EphemeralContainerStepRule> rules =
                Iterators.sequence(trait.getContainerStepRules(), globalConfig.getContainerStepRules());
        EphemeralContainerStepRuleEvaluator evaluator = new EphemeralContainerStepRuleEvaluator();
        evaluator.eval(step, rules);

        run(this::startEphemeralContainerWithRetry);
        return false;
    }

    /**
     * Attempt to start ephemeral container and retry is start failed. This function handles
     * common retry scenarios that may occur during heavy cluster load or lots of ephemeral
     * containers starting at once.
     * @throws Exception if container start fails or interrupted
     */
    protected void startEphemeralContainerWithRetry() throws Exception {
        StepContext context = getContext();
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(context);
        KubernetesSlave slave = nodeContext.getKubernetesSlave();
        TaskListener listener = context.get(TaskListener.class);
        MetricRegistry metrics = Metrics.metricRegistry();
        int retries = 0;
        do {
            try {
                startEphemeralContainer();
                break; // Success
            } catch (EphemeralContainerTerminatedException e) {
                String reason = e.getState().getReason();
                // Attempt to retry starting container if it terminated while starting due to a canceled containerd
                // context. This could happen if there is high system load resource constraints. The hope is that
                // by waiting or simply trying again the container successfully starts.
                if (retries < START_MAX_RETRY && START_RETRY_REASONS.contains(reason)) {
                    metrics.counter(
                                    io.jenkins.plugins.kubernetes.ephemeral.MetricNames
                                            .EPHEMERAL_CONTAINERS_CREATION_RETRIED)
                            .inc();
                    retries++;
                    // Add a little bit of wait in case the container was terminated because of high system load.
                    long waitTime = 0;
                    if (START_RETRY_MAX_WAIT > 0) {
                        waitTime =
                                ThreadLocalRandom.current().nextLong(TimeUnit.SECONDS.toMillis(START_RETRY_MAX_WAIT));
                    }

                    if (waitTime > 0) {
                        LOGGER.info("Ephemeral container terminated while starting with reason " + reason
                                + ", trying again in "
                                + waitTime + "ms (" + retries + " of " + START_MAX_RETRY + "): "
                                + e.getMessage());
                        Thread.sleep(waitTime);
                    } else {
                        LOGGER.info("Ephemeral container terminated while starting with reason " + reason
                                + ", trying again (" + retries + " of " + START_MAX_RETRY + "): " + e.getMessage());
                    }

                    printConsole(
                            listener,
                            "Ephemeral container terminated while starting with reason " + reason + ", trying again ("
                                    + retries + " of " + START_MAX_RETRY + ")");
                } else {
                    if (listener != null) {
                        // Attempt to explain common reasons why the container might not have started.
                        if (Strings.CS.contains(e.getState().getMessage(), "failed to create shim task: context")) {
                            printConsole(listener, """
                                     Based on the container termination message there are several reasons that could have caused the failure:
                                       Resource Constraints:
                                         - Insufficient memory or CPU resources
                                         - Resource limits being hit during startup
                                         - Node pressure or high system load""");
                        }

                        if (e.getState().getSignal() == null
                                && e.getState().getMessage() == null
                                && Strings.CS.equals(e.getState().getReason(), KUBE_REASON_ERROR)) {
                            printConsole(listener, """
                                     Based on the container termination message there are several reasons that could have caused the failure:
                                        Container Image:
                                          - The image platform architecture is not compatible with host node. For example
                                            a linux/arm64 image running on a linux/amd64 kubernetes node.""");
                        }
                    }

                    LOGGER.log(Level.FINEST, "Ephemeral container failed to start after " + retries + " retries", e);
                    throw new AbortException("Ephemeral container " + e.getContainerName() + " on Pod "
                            + slave.getPodName() + " failed to start: " + e.getMessage());
                }
            }
        } while (true);
    }

    /**
     * Start the ephemeral container by patching the current Pod spec and wait for it
     * to be ready. This function handles retry attempts if the patching operation
     * encounters conflicts.
     * @throws Exception container fails to start
     */
    private void startEphemeralContainer() throws Exception {
        LOGGER.log(Level.FINE, "Starting ephemeral container step.");
        StepContext context = getContext();
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(context);
        KubernetesSlave slave = nodeContext.getKubernetesSlave();
        KubernetesCloud cloud = slave.getKubernetesCloud();

        // Generate unique container name
        String stepId = ObjectUtils.hashCodeHex(this.step);
        String containerName = PodUtils.createNameWithRandomSuffix("jkns-step-" + stepId);

        // Create ephemeral container from container template
        EphemeralContainer ec = createEphemeralContainer(containerName, slave);

        LOGGER.finest(() -> "Adding Ephemeral Container: " + ec);
        // Display link in the build console to the new container
        TaskListener listener = context.get(TaskListener.class);
        String containerUrl = ModelHyperlinkNote.encodeTo(
                "/computer/" + nodeContext.getPodName() + "/container?name=" + containerName, containerName);
        if (listener != null) {
            String runningAs = "";
            SecurityContext sc = ec.getSecurityContext();
            if (sc != null) {
                runningAs = String.format(" (running as %s:%s)", sc.getRunAsUser(), sc.getRunAsGroup());
            }

            // Add link to the container logs
            printConsole(
                    listener,
                    "Starting ephemeral container " + containerUrl + " with image " + ec.getImage() + runningAs);
        }

        // Patch the Pod with the new ephemeral container
        PodResource podResource = nodeContext.getPodResource();
        MetricRegistry metrics = Metrics.metricRegistry();
        StopWatch startDuration = new StopWatch();
        startDuration.start();
        // Current implementation of ephemeral containers only allows ephemeral containers to be added
        // so patching may fail if different threads attempt to add using the same resource version
        // which would effectively act as a "delete" when the second patch was processed. If this
        // situation is detected the patch will be retried.
        int retries = 0;
        try {
            do {
                try {
                    podResource.ephemeralContainers().edit(pod -> new PodBuilder(pod)
                            .editSpec()
                            .addToEphemeralContainers(ec)
                            .endSpec()
                            .build());

                    break; // Success
                } catch (KubernetesClientException kce) {
                    Status status = kce.getStatus();
                    if (retries < PATCH_MAX_RETRY
                            && status != null
                            && Strings.CS.equals(status.getReason(), KUBE_REASON_CONFLICT)) {
                        retries++;

                        // With large parallel operations the max retry may still get hit trying to provision
                        // ephemeral container patch updates. This introduces a small amount of random wait
                        // to distribute the patch updates to help reduce the chances of a conflict.
                        long waitTime = 0;
                        if (status.getDetails() != null && status.getDetails().getRetryAfterSeconds() != null) {
                            waitTime = TimeUnit.SECONDS.toMillis(
                                    status.getDetails().getRetryAfterSeconds());
                        } else if (PATCH_RETRY_MAX_WAIT > 0) {
                            waitTime = ThreadLocalRandom.current()
                                    .nextLong(TimeUnit.SECONDS.toMillis(PATCH_RETRY_MAX_WAIT));
                        }

                        if (waitTime > 0) {
                            LOGGER.info("Ephemeral container patch failed due to optimistic locking, trying again in "
                                    + waitTime + "ms (" + retries + " of " + PATCH_MAX_RETRY + "): "
                                    + kce.getMessage());
                            Thread.sleep(waitTime);
                        } else {
                            LOGGER.info("Ephemeral container patch failed due to optimistic locking, trying again ("
                                    + retries + " of " + PATCH_MAX_RETRY + "): " + kce.getMessage());
                        }
                    } else {
                        throw kce;
                    }
                }
            } while (true);
        } catch (KubernetesClientException kce) {
            metrics.counter(io.jenkins.plugins.kubernetes.ephemeral.MetricNames.EPHEMERAL_CONTAINERS_CREATION_FAILED)
                    .inc();
            LOGGER.log(
                    Level.WARNING,
                    "Failed to add ephemeral container " + containerName + " to pod " + slave.getPodName()
                            + " on cloud " + cloud.name + " after " + retries + " retries.",
                    kce);
            String message = "Ephemeral container could not be added.";
            Status status = kce.getStatus();
            if (status != null) {
                if (status.getMessage() != null) {
                    message += " " + status.getMessage();
                }

                message += " (" + status.getReason() + ")";
            }

            if (retries == PATCH_MAX_RETRY) {
                message += ". Reached max retry limit.";
            }

            throw new AbortException(message);
        }

        // Wait until ephemeral container has started
        PodTemplate pt = slave.getTemplate();
        LOGGER.fine(
                () -> "Waiting for Ephemeral Container to start: " + containerName + " on Pod " + slave.getPodName());
        try {
            StopWatch waitDuration = new StopWatch();
            waitDuration.start();
            podResource.waitUntilCondition(
                    new EphemeralContainerRunningCondition(containerName, containerUrl, listener),
                    pt.getSlaveConnectTimeout(),
                    TimeUnit.SECONDS);
            LOGGER.fine(() -> "Ephemeral Container started: " + containerName + " on Pod " + slave.getPodName()
                    + " (waited " + waitDuration + ")");
            metrics.counter(io.jenkins.plugins.kubernetes.ephemeral.MetricNames.EPHEMERAL_CONTAINERS_CREATED)
                    .inc();
            metrics.histogram(
                            io.jenkins.plugins.kubernetes.ephemeral.MetricNames
                                    .EPHEMERAL_CONTAINERS_CREATION_WAIT_DURATION)
                    .update(waitDuration.getTime());
        } catch (KubernetesClientException kce) {
            metrics.counter(io.jenkins.plugins.kubernetes.ephemeral.MetricNames.EPHEMERAL_CONTAINERS_CREATION_FAILED)
                    .inc();
            if (kce instanceof EphemeralContainerTerminatedException) {
                // Propagate exception to caller to decide if we should retry or abort
                throw kce;
            }

            if (kce instanceof KubernetesClientTimeoutException) {
                String status;
                try {
                    status = EphemeralPodContainerSource.getEphemeralContainerStatus(podResource.get(), containerName)
                            .map(cs -> cs.getState().toString())
                            .orElse("no status available");
                } catch (KubernetesClientException ignored) {
                    status = "failed to get status";
                }

                throw new AbortException("Ephemeral container " + containerName + " on Pod " + slave.getPodName()
                        + " failed to start after " + pt.getSlaveConnectTimeout() + " seconds: " + status);
            }

            Throwable cause = kce.getCause();
            if (cause instanceof InterruptedException) {
                LOGGER.log(
                        Level.FINEST,
                        "Ephemeral container step interrupted " + containerName + " on Pod " + slave.getPodName(),
                        kce);
                return;
            } else {
                LOGGER.log(
                        Level.FINEST,
                        "Ephemeral container " + containerName + " on Pod " + slave.getPodName()
                                + " failed to start due to kubernetes client exception",
                        kce);
                throw new AbortException("Ephemeral container " + containerName + " on Pod " + slave.getPodName()
                        + " failed to start: " + kce.getMessage());
            }
        }

        // capture total container ready duration
        metrics.histogram(MetricNames.EPHEMERAL_CONTAINERS_CREATION_DURATION).update(startDuration.getTime());
        printConsole(
                listener,
                "Ephemeral container " + containerName + " ready after "
                        + startDuration.getDuration().toSeconds() + " seconds");

        EnvironmentExpander env = EnvironmentExpander.merge(
                context.get(EnvironmentExpander.class),
                EnvironmentExpander.constant(Collections.singletonMap("POD_CONTAINER", containerName)));

        EnvVars globalVars = null;
        Jenkins instance = Jenkins.get();

        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties =
                instance.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList =
                globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class);
        if (envVarsNodePropertyList != null && !envVarsNodePropertyList.isEmpty()) {
            globalVars = envVarsNodePropertyList.get(0).getEnvVars();
        }

        EnvVars rcEnvVars = null;
        Run<?, ?> run = context.get(Run.class);
        if (run != null && listener != null) {
            rcEnvVars = run.getEnvironment(listener);
        }

        decorator = new EphemeralContainerExecDecorator();
        decorator.setNodeContext(nodeContext);
        decorator.setContainerName(containerName);
        decorator.setEnvironmentExpander(env);
        decorator.setGlobalVars(globalVars);
        decorator.setRunContextEnvVars(rcEnvVars);
        decorator.setShell(step.getShell());
        context.newBodyInvoker()
                .withContexts(BodyInvoker.mergeLauncherDecorators(context.get(LauncherDecorator.class), decorator), env)
                .withCallback(closeQuietlyCallback(decorator))
                .withCallback(new TerminateEphemeralContainerExecCallback(containerName))
                .start();
    }

    @Override
    @SuppressFBWarnings(
            value = "NP_NULL_ON_SOME_PATH",
            justification = "decorator is null checked and context is marked non-null")
    public void stop(@NonNull Throwable cause) throws Exception {
        LOGGER.finest("Stopping ephemeral container step.");
        super.stop(cause);
        if (decorator != null) {
            StepContext context = getContext();
            closeQuietly(context, decorator);
            terminateEphemeralContainer(context, decorator.getContainerName());
        }
    }

    /**
     * Print message to listener logger.
     * @param listener task listener or {@code null}
     * @param message message to print
     */
    @SuppressFBWarnings(
            value = "DCN_NULLPOINTER_EXCEPTION",
            justification = "misbehaving logger plugins should not stop prevent container termination")
    private static void printConsole(@CheckForNull TaskListener listener, String message) {
        if (listener != null) {
            try {
                listener.getLogger().println(message);
            } catch (NullPointerException ignore) {
                // can't trust all plugins manipulating the console log to handle multi-threading correctly
                // (i.e. splunk-devops PipelineConsoleDecoder is not thread safe)
            }
        }
    }

    private EphemeralContainer createEphemeralContainer(String containerName, KubernetesSlave slave)
            throws IOException, InterruptedException {
        Pod pod = slave.getPod().orElseThrow(() -> new AbortException("Kubernetes node Pod reference not found."));
        EphemeralContainer ec = KubernetesClientModelFactory.createEphemeralContainer(containerName, step, pod);

        // fill in run as user/group from the current agent if not explicitly set
        SecurityContext sc = ec.getSecurityContext();
        if (sc == null || (sc.getRunAsUser() == null && sc.getRunAsGroup() == null)) {
            if (sc == null) {
                sc = new SecurityContext();
                ec.setSecurityContext(sc);
            }

            setDefaultRunAsUser(sc);
        }

        return ec;
    }

    private void setDefaultRunAsUser(SecurityContext sc) throws IOException, InterruptedException {
        Launcher launcher = getContext().get(Launcher.class);
        if (launcher != null && launcher.isUnix()) {
            ByteArrayOutputStream userId = new ByteArrayOutputStream();
            launcher.launch()
                    .cmds("id", "-u")
                    .quiet(true)
                    .stdout(userId)
                    .start()
                    .joinWithTimeout(WHOAMI_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());

            ByteArrayOutputStream groupId = new ByteArrayOutputStream();
            launcher.launch()
                    .cmds("id", "-g")
                    .quiet(true)
                    .stdout(groupId)
                    .start()
                    .joinWithTimeout(WHOAMI_TIMEOUT, TimeUnit.SECONDS, launcher.getListener());

            final Charset charset = Charset.defaultCharset();
            sc.setRunAsUser(NumberUtils.createLong(userId.toString(charset).trim()));
            sc.setRunAsGroup(NumberUtils.createLong(groupId.toString(charset).trim()));
        }
    }

    private static void terminateEphemeralContainer(StepContext context, String containerName) throws Exception {
        LOGGER.fine(() -> "Removing ephemeral container: " + containerName);
        KubernetesNodeContext nodeContext = new KubernetesNodeContext(context);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PodResource resource = nodeContext.getPodResource();
        try (ExecWatch ignored = resource.inContainer(containerName)
                .redirectingInput()
                .writingOutput(out)
                .writingError(out)
                .withTTY()
                .exec(containerStopCommand(containerName))) {
            resource.waitUntilCondition(
                    new EphemeralContainerStatusCondition(containerName, false), 10, TimeUnit.SECONDS);
            LOGGER.finest(() -> "Ephemeral Container stopped: " + nodeContext.getPodName() + "/" + containerName);
        } catch (Exception ex) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to terminate ephemeral container " + containerName + " on pod " + nodeContext.getPodName(),
                    ex);
        }

        LOGGER.finest(() -> {
            try {
                ContainerStatus status = EphemeralPodContainerSource.getEphemeralContainerStatus(
                                resource.get(), containerName)
                        .orElse(null);
                return "Ephemeral container status after step: " + nodeContext.getPodName() + "/" + containerName
                        + " -> " + status;
            } catch (KubernetesClientException ignored) {
                return "Failed to get container status after step";
            }
        });
    }

    private static class TerminateEphemeralContainerExecCallback extends BodyExecutionCallback.TailCall {

        @Serial
        private static final long serialVersionUID = 6385838254761750483L;

        private final String containerName;

        private TerminateEphemeralContainerExecCallback(String containerName) {
            this.containerName = containerName;
        }

        @Override
        public void finished(StepContext context) throws Exception {
            terminateEphemeralContainer(context, containerName);
        }
    }

    /**
     * Predicate for an ephemeral container that passes when the container state enters
     * either running or terminated.
     */
    private static class EphemeralContainerStatusCondition implements Predicate<Pod> {
        protected final String containerName;
        private final boolean running;

        EphemeralContainerStatusCondition(String containerName, boolean running) {
            this.containerName = containerName;
            this.running = running;
        }

        @Override
        public boolean test(Pod pod) {
            // pod could be null if informer list is empty
            if (pod == null) {
                return !running;
            }

            return pod.getStatus().getEphemeralContainerStatuses().stream()
                    .filter(status -> Strings.CS.equals(status.getName(), containerName))
                    .anyMatch(status -> {
                        onStatus(status);
                        if (running) {
                            return status.getState().getRunning() != null;
                        } else {
                            return status.getState().getTerminated() != null;
                        }
                    });
        }

        protected void onStatus(ContainerStatus status) {}
    }

    /**
     * Predicate that passes when a specific ephemeral container is running. If the container enters
     * a terminated state then a {@link EphemeralContainerTerminatedException} will be thrown to
     * interrupt the wait condition.
     */
    private static class EphemeralContainerRunningCondition extends EphemeralContainerStatusCondition {

        private static final Set<String> IGNORE_REASONS =
                Set.of(KUBE_REASON_CONTAINER_CREATING, KUBE_REASON_POD_INITIALIZING);

        @CheckForNull
        private final TaskListener taskListener;

        private final String containerUrl;

        EphemeralContainerRunningCondition(
                String containerName, String containerUrl, @CheckForNull TaskListener listener) {
            super(containerName, true);
            this.containerUrl = containerUrl;
            this.taskListener = listener;
        }

        @Override
        protected void onStatus(ContainerStatus status) {
            // Stop waiting if the container already terminated
            ContainerStateTerminated terminated = status.getState().getTerminated();
            if (terminated != null) {
                printConsole(
                        taskListener,
                        "Ephemeral container " + containerUrl + " failed to start: " + terminated.getMessage() + " ("
                                + terminated.getReason() + ")");

                throw new EphemeralContainerTerminatedException(containerName, terminated);
            }

            if (taskListener != null) {
                ContainerStateWaiting waiting = status.getState().getWaiting();
                // skip initial "ContainerCreating" event
                if (waiting != null && !IGNORE_REASONS.contains(waiting.getReason())) {
                    StringBuilder logMsg =
                            new StringBuilder().append("Ephemeral container ").append(containerUrl);
                    String message = waiting.getMessage();
                    if (message != null) {
                        logMsg.append(" ").append(message);
                    }

                    logMsg.append(" (").append(waiting.getReason()).append(")");
                    printConsole(taskListener, logMsg.toString());
                }
            }
        }
    }

    /**
     * Exception thrown by {@link EphemeralContainerRunningCondition} if the container enters a terminated
     * state while waiting to start. This will immediately exit the waitUntilCondition.
     */
    private static class EphemeralContainerTerminatedException extends KubernetesClientException {

        @Serial
        private static final long serialVersionUID = 3455221650416693019L;

        private final String containerName;
        private final ContainerStateTerminated state;

        EphemeralContainerTerminatedException(@NonNull String containerName, @NonNull ContainerStateTerminated state) {
            super("container terminated while waiting to start: " + state);
            this.containerName = containerName;
            this.state = state;
        }

        public ContainerStateTerminated getState() {
            return state;
        }

        public String getContainerName() {
            return containerName;
        }
    }
}
