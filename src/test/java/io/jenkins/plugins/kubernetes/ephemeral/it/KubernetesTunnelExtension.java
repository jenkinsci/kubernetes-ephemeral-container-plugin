package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.jupiter.api.Assumptions.*;

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
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test extension that exposes from the provided Kubernetes cluster namespace to
 * ths test Jenkins instance running outside the cluster. This tunnel is created
 * using the <a href="https://github.com/omrikiei/ktunnel">ktunnel</a> command.
 *
 * @see #ktunnelCmd()
 */
public class KubernetesTunnelExtension implements BeforeAllCallback, AfterAllCallback {

    private final String namespace;
    private Process tunnel;
    private String tunnelUrl;

    public KubernetesTunnelExtension(@NonNull String namespace) {
        this.namespace = namespace;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        int jenkinsPort = getOrSetSystemProperty("port", 8000);
        int slaveAgentPort = getOrSetSystemProperty("jenkins.model.Jenkins.slaveAgentPort", 8001);
        String tunnelHost = "jenkins";
        tunnelUrl = new URL("http", tunnelHost, jenkinsPort, "/jenkins").toString();
        ProcessBuilder bldr = new ProcessBuilder(
                        ktunnelCmd(),
                        "expose",
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
            assumeTrue(false, "ktunnel not available " + ioe);
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

    @Override
    public void afterAll(ExtensionContext context) {
        if (tunnel != null && tunnel.isAlive()) {
            tunnel.destroy();
        }
    }

    private int getOrSetSystemProperty(String name, int defaultVal) {
        String v = System.getProperty(name);
        if (v == null) {
            System.setProperty(name, Integer.toString(defaultVal));
        }

        return Integer.getInteger(name);
    }

    /**
     * Get the ktunnel command to execute. When in CI environment look for downloaded binary
     * in target directory (via maven wget goal), otherwise use the system path.
     * @return ktunnel command
     */
    private String ktunnelCmd() {
        // in a CI environment use the binary downloaded by Maven.
        if (Strings.CI.equals(System.getenv("CI"), "true")) {
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
