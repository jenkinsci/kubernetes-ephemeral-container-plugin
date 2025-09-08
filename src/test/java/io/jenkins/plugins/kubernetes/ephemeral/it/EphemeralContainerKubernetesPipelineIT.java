package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerGlobalConfiguration;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerKubernetesCloudTrait;
import io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepRule;
import io.jenkins.plugins.kubernetes.ephemeral.rules.ContainerImageRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.PodLabel;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.GlobalDefaultFlowDurabilityLevel;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Integration tests run pipeline jobs using a Kubernetes Cloud agent. Requires a local Kubernetes
 * cluster and the ktunnel command available. In a CI environment, maven will download kind and
 * ktunnel to satisfy these requirements.
 *
 * @see KubernetesInDockerExtension
 * @see KubernetesTunnelExtension
 * @see KubernetesNamespaceExtension
 */
@WithJenkins
class EphemeralContainerKubernetesPipelineIT {

    private static final String TESTING_NAMESPACE = "kubernetes-ephemeral-container-plugin";

    @SuppressWarnings("unused")
    @RegisterExtension
    @Order(0)
    private static final KubernetesInDockerExtension KIND_EXTENSION = new KubernetesInDockerExtension();

    @SuppressWarnings("unused")
    @RegisterExtension
    @Order(1)
    private static final KubernetesNamespaceExtension NAMESPACE_EXTENSION =
            new KubernetesNamespaceExtension(TESTING_NAMESPACE);

    @RegisterExtension
    @Order(2)
    private static final KubernetesTunnelExtension TUNNEL_EXTENSION = new KubernetesTunnelExtension(TESTING_NAMESPACE);

    private JenkinsRule j;

    private String name;

    private KubernetesCloud cloud;
    private WorkflowJob job;

    @BeforeEach
    void beforeEach(TestInfo info, JenkinsRule rule) throws Exception {
        j = rule;
        name = info.getTestMethod().orElseThrow().getName();
        cloud = new KubernetesCloud("kubernetes");
        cloud.setPodLabels(List.of(
                new PodLabel("build-number", PodUtils.generateRandomSuffix()),
                new PodLabel("test-class", getClass().getSimpleName()),
                new PodLabel("test-method", name)));
        cloud.setTraits(List.of(new EphemeralContainerKubernetesCloudTrait()));

        cloud.setNamespace(TESTING_NAMESPACE);
        cloud.addTemplate(buildBusyboxTemplate("busybox"));

        String tunnelUrl = TUNNEL_EXTENSION.getJenkinsTunnelUrl();
        cloud.setJenkinsUrl(tunnelUrl);
        cloud.setWebSocket(true);

        j.jenkins.clouds.add(cloud);
        j.jenkins.save();

        // Had some problems with FileChannel.close hangs from WorkflowRun.save:
        j.jenkins
                .getDescriptorByType(GlobalDefaultFlowDurabilityLevel.DescriptorImpl.class)
                .setDurabilityHint(FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
        job = createJob(name);
        job.setDefinition(new CpsFlowDefinition(loadPipelineDefinition(), true));
    }

    public static String generateProjectName(String name) {
        return StringUtils.capitalize(name.replaceAll("([A-Z])", " $1"));
    }

    private WorkflowJob createJob(String name) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, generateProjectName(name));
        job.setDefinition(new CpsFlowDefinition(loadPipelineDefinition(), true));
        return job;
    }

    private WorkflowRun scheduleJob() throws ExecutionException, InterruptedException {
        QueueTaskFuture<WorkflowRun> task = job.scheduleBuild2(0);
        var run = task == null ? null : task.waitForStart();
        System.err.println("Job '" + job.getName() + "' started");
        return run;
    }

    private WorkflowRun scheduleJobAndWaitComplete() throws ExecutionException, InterruptedException {
        return j.waitForCompletion(scheduleJob());
    }

    private String loadPipelineDefinition() throws IOException {
        String scriptFile = name + ".groovy";
        try (InputStream in = EphemeralContainerKubernetesPipelineIT.class.getResourceAsStream(scriptFile)) {
            assumeTrue(in != null, scriptFile + " not found");
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    private void evictAgentPod() {
        System.err.println("Evicting agent pods");
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.pods()
                    .inNamespace(TESTING_NAMESPACE)
                    .withLabel("test-method", name)
                    .resources()
                    .forEach(r -> {
                        System.err.println(
                                "Evicting pod " + r.get().getMetadata().getName());
                        r.evict();
                    });
        }
    }

    private PodTemplate buildBusyboxTemplate(String label) {
        // Create a busybox template
        PodTemplate podTemplate = new PodTemplate();
        podTemplate.setLabel(label);
        podTemplate.setTerminationGracePeriodSeconds(0L);
        return podTemplate;
    }

    @Test
    void ephemeralContainerEnabled() throws Exception {
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=true", run);
        j.assertLogContains("+ node --version\nv22", run);
        j.assertLogContains("+ echo 'alternate step name'", run);
    }

    @Test
    void ephemeralContainerNotEnabled() throws Exception {
        cloud.setTraits(null);
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=false", run);
        j.assertLogContains("ERROR: Ephemeral containers not enabled on kubernetes", run);
    }

    @Test
    void imageNotAllowedByCloudTrait() throws Exception {
        Optional<EphemeralContainerKubernetesCloudTrait> trait =
                cloud.getTrait(EphemeralContainerKubernetesCloudTrait.class);
        trait.ifPresent(t -> t.setContainerStepRules(
                List.of(new ContainerImageRule("*/node", EphemeralContainerStepRule.Action.REJECT))));
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("ERROR: Image 'node:22-alpine' has been disallowed by Jenkins administrators.", run);
    }

    @Test
    void imageNotAllowedByGlobalConfig() throws Exception {
        EphemeralContainerGlobalConfiguration config = EphemeralContainerGlobalConfiguration.get();
        config.setContainerStepRules(
                List.of(new ContainerImageRule("*/node", EphemeralContainerStepRule.Action.REJECT)));
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatus(Result.FAILURE, run);
        j.assertLogContains("ERROR: Image 'node:22-alpine' has been disallowed by Jenkins administrators.", run);
    }

    @Test
    void ephemeralContainerEnvVars() throws Exception {
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("+ echo 'foo=baz'", run);
        j.assertLogContains("+ echo 'bar=bats'", run);
    }

    @Test
    void sharedWorkingDir() throws Exception {
        var run = scheduleJobAndWaitComplete();
        j.assertBuildStatusSuccess(run);
        j.assertLogContains("+ cat foo.txt\nfoo\n[Pipeline]", run);
        j.assertLogContains("+ cat foo.txt\nfoo\nbar", run);
        j.assertLogContains("+ cat dogs.txt\nsnoopy", run);
    }

    @Test
    void buildAborted() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        SemaphoreStep.waitForStart("ephemeralContainer/1", run);
        Executor e = run.getExecutor();
        assertNotNull(e, "expected executor");
        e.interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        j.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    void stepAborted() throws Exception {
        var run = scheduleJob();
        j.assertBuildStatus(Result.FAILURE, j.waitForCompletion(run));
        j.assertLogContains("ERROR: abort step in ephemeral container", run);
        j.assertLogContains("Finished: FAILURE", run);
    }

    @Test
    void podTerminated() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        SemaphoreStep.waitForStart("ephemeralContainer/1", run);
        evictAgentPod();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        j.assertLogContains("Agent was removed", run);
        j.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    void timeoutInterrupt() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
        j.assertLogContains("Timeout has been exceeded", run);
        j.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    void nestedContainersRedis() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(run));
        j.assertLogContains("+ redis-cli ping\nPONG", run);
        j.assertLogContains("Finished: SUCCESS", run);
    }
}
