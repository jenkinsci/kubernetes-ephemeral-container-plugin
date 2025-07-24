package io.jenkins.plugins.kubernetes.ephemeral.it;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.rules.RunRules;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

/**
 * Test rule that starts Jenkins and creates a tunnel to the provided Kubernetes namespace.
 *
 * @see JenkinsRule
 */
public class KubernetesJenkinsRule implements TestRule {

    public final JenkinsRule jkrule;
    private final String namespace;

    public KubernetesJenkinsRule(String namespace) {
        this.jkrule = new JenkinsRule();
        this.namespace = namespace;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new RunRules(base, List.of(jkrule), description);
    }

    public Jenkins getJenkins() {
        return jkrule.jenkins;
    }

    public void evictAgentPod(TestName name) {
        System.err.println("Evicting agent pods");
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            client.pods()
                    .inNamespace(namespace)
                    .withLabel("test-method", name.getMethodName())
                    .resources()
                    .forEach(r -> {
                        System.err.println(
                                "Evicting pod " + r.get().getMetadata().getName());
                        r.evict();
                    });
        }
    }
}
