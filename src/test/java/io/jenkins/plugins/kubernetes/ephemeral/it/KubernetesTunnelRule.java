package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.Assume.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test rule that exposes from the provided Kubernetes cluster namespace to
 * ths test Jenkins instance running outside the cluster. This tunnel is created
 * using the <a href="https://github.com/omrikiei/ktunnel">ktunnel</a> command.
 *
 * @see #ktunnelCmd()
 * @see JenkinsRule
 */
public class KubernetesTunnelRule implements TestRule {

    private final JenkinsRule jenkins;
    private final String namespace;
    private Process tunnel;
    private String tunnelUrl;

    public KubernetesTunnelRule(@NonNull JenkinsRule jenkins, @NonNull String namespace) {
        this.jenkins = jenkins;
        this.namespace = namespace;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                start();
                try {
                    base.evaluate();
                } finally {
                    stop();
                }
            }
        };
    }

    public void start() throws IOException, InterruptedException {
        if (tunnel == null || !tunnel.isAlive()) {
            startTunnel();
        }
    }

    public void stop() {
        if (tunnel.isAlive()) {
            tunnel.destroy();
        }
    }

    private void startTunnel() throws IOException, InterruptedException {
        URL jenkinsUrl = jenkins.getURL();
        int jenkinsPort = jenkinsUrl.getPort();
        int slaveAgentPort = jenkins.jenkins.tcpSlaveAgentListener.getPort();
        String tunnelHost = "jenkins";
        tunnelUrl = new URL(jenkinsUrl.getProtocol(), tunnelHost, jenkinsPort, jenkinsUrl.getFile()).toString();
        ProcessBuilder bldr = new ProcessBuilder(
                        ktunnelCmd(),
                        "expose",
                        "--reuse",
                        "--namespace",
                        namespace,
                        tunnelHost,
                        jenkinsPort + ":" + jenkinsPort,
                        slaveAgentPort + ":" + slaveAgentPort)
                .inheritIO();
        // system property used by fabric8 client, could be set by KubernetesInDockerRule when in CI mode
        // https://github.com/fabric8io/kubernetes-client?tab=readme-ov-file#configuring-the-client
        String kubeconfig = System.getProperty("kubeconfig");
        if (!StringUtils.isEmpty(kubeconfig)) {
            bldr.environment().put("KUBECONFIG", kubeconfig);
        }

        System.err.println("Launching " + String.join(" ", bldr.command()));
        try {
            tunnel = bldr.start();
        } catch (IOException ioe) {
            assumeNoException("ktunnel not available", ioe);
        }

        Thread.sleep(2000);
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName("jenkins")
                    .waitUntilReady(30, TimeUnit.SECONDS);
            System.err.println("ktunnel deployment ready");
        } catch (KubernetesClientException re) {
            System.err.println("ktunnel failed to start");
            throw re;
        }
    }

    /**
     * Get the ktunnel command to execute. When in CI environment look for downloaded binary
     * in target directory (via maven wget goal), otherwise use the system path.
     * @return ktunnel command
     */
    private String ktunnelCmd() {
        // in a CI environment use the binary downloaded by Maven.
        if (StringUtils.equalsIgnoreCase(System.getenv("CI"), "true")) {
            String ktunnelExe = SystemUtils.IS_OS_WINDOWS ? "ktunnel.exe" : "ktunnel";
            Path ktunnel = Paths.get("target", "ktunnel", ktunnelExe);
            if (Files.exists(ktunnel) && Files.isRegularFile(ktunnel) && Files.isExecutable(ktunnel)) {
                System.err.println("Using " + ktunnel);
                return ktunnel.toString();
            }
        }

        return "ktunnel";
    }

    /**
     * Get the tunnel to Jenkins. This can be used by cloud agents to reach Jenkins
     * outside the Kubernetes cluster.
     * @return tunnel url, non-null at test execution
     */
    public String getJenkinsTunnelUrl() {
        return tunnelUrl;
    }
}
