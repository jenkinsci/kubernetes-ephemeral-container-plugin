package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test extension that creates a new <a href="https://kind.sigs.k8s.io/">kind</a> cluster for CI testing environments.
 * @see #kindCmd()
 */
class KubernetesInDockerExtension implements BeforeAllCallback, AfterAllCallback {

    private String cluster;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (Strings.CI.equals(System.getenv("CI"), "true")) {
            cluster = createCluster();
        } else {
            System.err.println("Using local Kubernetes cluster");
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (cluster != null) {
            deleteCluster(cluster);
        }
    }

    private String createCluster() throws InterruptedException {
        String cluster = "ci" + RandomStringUtils.insecure().nextNumeric(5);
        Path kubeconfig = Paths.get("target", "kubeconfig-" + cluster).toAbsolutePath();
        System.out.println("setting KUBECONFIG=" + kubeconfig);
        // system property used by fabric8 client
        // https://github.com/fabric8io/kubernetes-client?tab=readme-ov-file#configuring-the-client
        System.setProperty("kubeconfig", kubeconfig.toString());
        ProcessBuilder bldr =
                new ProcessBuilder(kindCmd(), "create", "cluster", "--name", cluster, "--wait", "5m").inheritIO();
        bldr.environment().put("KUBECONFIG", kubeconfig.toString());
        try {
            int code = bldr.start().waitFor();
            assertEquals(0, code, "kind cluster not created successfully");
        } catch (IOException ioe) {
            assumeTrue(false, ioe.toString());
        }

        System.err.println("kind cluster " + cluster + " created");
        return cluster;
    }

    private void deleteCluster(String cluster) throws IOException, InterruptedException {
        ProcessBuilder bldr = new ProcessBuilder(kindCmd(), "delete", "cluster", "--name", cluster).inheritIO();
        int code = bldr.start().waitFor();
        if (code == 0) {
            System.err.println("kind cluster " + cluster + " deleted");
        } else {
            System.err.println("kind cluster " + cluster + " deletion returned code " + code);
        }
    }

    /**
     * Get the kind command to execute. When in CI environment look for downloaded binary
     * in target directory (via maven wget goal), otherwise use the system path.
     * @return kind command
     */
    private String kindCmd() {
        // in a CI environment use the binary downloaded by Maven.
        if (Strings.CI.equals(System.getenv("CI"), "true")) {
            Path kind = Paths.get("target", "kind", "kind");
            if (Files.exists(kind)) {
                System.err.println("Using " + kind);
                return kind.toString();
            }
        }

        return "kind";
    }
}
