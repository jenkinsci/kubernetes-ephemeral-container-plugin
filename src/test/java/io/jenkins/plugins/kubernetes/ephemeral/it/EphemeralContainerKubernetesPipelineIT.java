package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import hudson.model.Executor;
import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
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
import org.apache.commons.lang.StringUtils;
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
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

/**
 * Integration tests run pipeline jobs using a Kubernetes Cloud agent. Requires a local Kubernetes
 * cluster and the ktunnel command available. In a CI environment, maven will download kind and
 * ktunnel to satisfy these requirements.
 *
 * @see KubernetesInDockerRule
 * @see KubernetesTunnelRule
 * @see KubernetesNamespaceRule
 * @see KubernetesJenkinsRule
 */
public class EphemeralContainerKubernetesPipelineIT {

    private static final String testingNamespace = "kubernetes-ephemeral-container-plugin";

    @ClassRule(order = 0)
    public static KubernetesInDockerRule kindRule = new KubernetesInDockerRule();

    @ClassRule(order = 1)
    public static KubernetesNamespaceRule namespaceRule = new KubernetesNamespaceRule(testingNamespace);

    @Rule
    public final KubernetesJenkinsRule k = new KubernetesJenkinsRule(testingNamespace);

    @Rule
    public final TestName name = new TestName();

    @Rule
    public final Timeout globalTimeout = Timeout.seconds(240);

    private KubernetesCloud cloud;
    private WorkflowJob job;

    @Before
    public void setupCloud() throws IOException {
        cloud = new KubernetesCloud("kubernetes");
        cloud.setPodLabels(List.of(
                new PodLabel("build-number", PodUtils.generateRandomSuffix()),
                new PodLabel("test-class", getClass().getSimpleName()),
                new PodLabel("test-method", name.getMethodName())));
        cloud.setTraits(List.of(new EphemeralContainerKubernetesCloudTrait()));

        cloud.setNamespace(testingNamespace);
        cloud.addTemplate(buildBusyboxTemplate("busybox"));

        String tunnelUrl = k.getJenkinsTunnelUrl();
        cloud.setJenkinsUrl(tunnelUrl);
        cloud.setWebSocket(true);

        k.getJenkins().clouds.add(cloud);
        k.getJenkins().save();
    }

    @Before
    public void setupTest() throws Exception {
        // Had some problems with FileChannel.close hangs from WorkflowRun.save:
        k.getJenkins()
                .getDescriptorByType(GlobalDefaultFlowDurabilityLevel.DescriptorImpl.class)
                .setDurabilityHint(FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
        job = createJob(name.getMethodName());
        job.setDefinition(new CpsFlowDefinition(loadPipelineDefinition(), true));
    }

    public static String generateProjectName(String name) {
        return StringUtils.capitalize(name.replaceAll("([A-Z])", " $1"));
    }

    private WorkflowJob createJob(String name) throws Exception {
        WorkflowJob j = k.getJenkins().createProject(WorkflowJob.class, generateProjectName(name));
        j.setDefinition(new CpsFlowDefinition(loadPipelineDefinition(), true));
        return j;
    }

    private WorkflowRun scheduleJob() throws ExecutionException, InterruptedException {
        QueueTaskFuture<WorkflowRun> task = job.scheduleBuild2(0);
        var run = task == null ? null : task.waitForStart();
        System.err.println("Job '" + job.getName() + "' started");
        return run;
    }

    private WorkflowRun scheduleJobAndWaitComplete() throws ExecutionException, InterruptedException {
        return k.jkrule.waitForCompletion(scheduleJob());
    }

    private String loadPipelineDefinition() throws IOException {
        String scriptFile = name.getMethodName() + ".groovy";
        try (InputStream in = EphemeralContainerKubernetesPipelineIT.class.getResourceAsStream(scriptFile)) {
            assumeTrue(scriptFile + " not found", in != null);
            return IOUtils.toString(in, StandardCharsets.UTF_8);
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
    public void ephemeralContainerEnabled() throws Exception {
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatusSuccess(run);
        k.jkrule.assertLogContains("KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=true", run);
        k.jkrule.assertLogContains("+ node --version\nv22", run);
        k.jkrule.assertLogContains("+ echo 'alternate step name'", run);
    }

    @Test
    public void ephemeralContainerNotEnabled() throws Exception {
        cloud.setTraits(null);
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatus(Result.FAILURE, run);
        k.jkrule.assertLogContains("KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=false", run);
        k.jkrule.assertLogContains("ERROR: Ephemeral containers not enabled on kubernetes", run);
    }

    @Test
    public void imageNotAllowedByCloudTrait() throws Exception {
        Optional<EphemeralContainerKubernetesCloudTrait> trait =
                cloud.getTrait(EphemeralContainerKubernetesCloudTrait.class);
        trait.ifPresent(t -> t.setContainerStepRules(
                List.of(new ContainerImageRule("*/node", EphemeralContainerStepRule.Action.REJECT))));
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatus(Result.FAILURE, run);
        k.jkrule.assertLogContains("ERROR: Image 'node:22-alpine' has been disallowed by Jenkins administrators.", run);
    }

    @Test
    public void imageNotAllowedByGlobalConfig() throws Exception {
        EphemeralContainerGlobalConfiguration config = EphemeralContainerGlobalConfiguration.get();
        config.setContainerStepRules(
                List.of(new ContainerImageRule("*/node", EphemeralContainerStepRule.Action.REJECT)));
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatus(Result.FAILURE, run);
        k.jkrule.assertLogContains("ERROR: Image 'node:22-alpine' has been disallowed by Jenkins administrators.", run);
    }

    @Test
    public void ephemeralContainerEnvVars() throws Exception {
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatusSuccess(run);
        k.jkrule.assertLogContains("+ echo 'foo=baz'", run);
        k.jkrule.assertLogContains("+ echo 'bar=bats'", run);
    }

    @Test
    public void sharedWorkingDir() throws Exception {
        var run = scheduleJobAndWaitComplete();
        k.jkrule.assertBuildStatusSuccess(run);
        k.jkrule.assertLogContains("+ cat foo.txt\nfoo\n[Pipeline]", run);
        k.jkrule.assertLogContains("+ cat foo.txt\nfoo\nbar", run);
        k.jkrule.assertLogContains("+ cat dogs.txt\nsnoopy", run);
    }

    @Test
    public void buildAborted() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        SemaphoreStep.waitForStart("ephemeralContainer/1", run);
        Executor e = run.getExecutor();
        assertNotNull("expected executor", e);
        e.interrupt();
        k.jkrule.assertBuildStatus(Result.ABORTED, k.jkrule.waitForCompletion(run));
        k.jkrule.assertLogContains("Aborted by unknown", run);
        k.jkrule.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    public void stepAborted() throws Exception {
        var run = scheduleJob();
        k.jkrule.assertBuildStatus(Result.FAILURE, k.jkrule.waitForCompletion(run));
        k.jkrule.assertLogContains("ERROR: abort build in ephemeral container\nFinished: FAILURE", run);
    }

    @Test
    public void podTerminated() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        SemaphoreStep.waitForStart("ephemeralContainer/1", run);
        k.evictAgentPod(name);
        k.jkrule.assertBuildStatus(Result.ABORTED, k.jkrule.waitForCompletion(run));
        k.jkrule.assertLogContains("Agent was removed", run);
        k.jkrule.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    public void timeoutInterrupt() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        k.jkrule.assertBuildStatus(Result.ABORTED, k.jkrule.waitForCompletion(run));
        k.jkrule.assertLogContains("Timeout has been exceeded", run);
        k.jkrule.assertLogContains("Finished: ABORTED", run);
    }

    @Test
    public void nestedContainersRedis() throws Exception {
        var run = scheduleJob();
        assertNotNull(run);
        k.jkrule.assertBuildStatus(Result.SUCCESS, k.jkrule.waitForCompletion(run));
        k.jkrule.assertLogContains("+ redis-cli ping\nPONG", run);
        k.jkrule.assertLogContains("Finished: SUCCESS", run);
    }
}
