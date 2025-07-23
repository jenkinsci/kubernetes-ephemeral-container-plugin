package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Test rule that creates a new <a href="https://kind.sigs.k8s.io/">kind</a> cluster for CI testing environments.
 * @see #kindCmd()
 */
public class KubernetesInDockerRule implements TestRule {

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                String cluster = null;
                if (StringUtils.equalsIgnoreCase(System.getenv("CI"), "true")) {
                    cluster = createCluster();
                } else {
                    System.err.println("Using local Kubernetes cluster");
                }

                try {
                    base.evaluate();
                } finally {
                    if (cluster != null) {
                        deleteCluster(cluster);
                    }
                }
            }
        };
    }

    private String createCluster() throws IOException, InterruptedException {
        String cluster = "ci" + RandomStringUtils.randomNumeric(5);
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
            assertEquals("kind cluster not created successfully", 0, code);
        } catch (IOException ioe) {
            assumeNoException(ioe);
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
        if (StringUtils.equalsIgnoreCase(System.getenv("CI"), "true")) {
            Path kind = Paths.get("target", "kind", "kind");
            if (Files.exists(kind)) {
                System.err.println("Using " + kind);
                return kind.toString();
            }
        }

        return "kind";
    }
}
