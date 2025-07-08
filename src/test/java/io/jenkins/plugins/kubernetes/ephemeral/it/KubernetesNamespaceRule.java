package io.jenkins.plugins.kubernetes.ephemeral.it;

import static org.junit.Assume.assumeNoException;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Test rule that creates the given kubernetes namespace and delete it after.
 */
public class KubernetesNamespaceRule implements TestRule {

    private final String namespace;

    public KubernetesNamespaceRule(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                KubernetesNamespaceRule.assumeKubernetes();
                AtomicBoolean created = new AtomicBoolean(true);
                Namespace ns = new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .endMetadata()
                        .build();
                try (KubernetesClient client = newClient()) {
                    System.err.println("Creating namespace " + namespace);
                    Resource<Namespace> nsResource = client.namespaces().resource(ns);
                    if (nsResource.isReady()) {
                        CountDownLatch deleteLatch = new CountDownLatch(1);
                        try (Watch w = nsResource.watch(new Watcher<>() {
                            @Override
                            public void eventReceived(Action action, Namespace resource) {
                                if (action == Action.DELETED) {
                                    deleteLatch.countDown();
                                }
                            }

                            @Override
                            public void onClose(WatcherException cause) {
                                deleteLatch.countDown();
                            }
                        })) {
                            System.err.println("Namespace " + namespace + " already exists, deleting...");
                            nsResource.delete();
                            if (!deleteLatch.await(1, TimeUnit.MINUTES)) {
                                System.err.println("Namespace delete not complete");
                            }
                        }
                    }

                    nsResource.createOr(nsOp -> {
                        System.err.println("Namespace " + namespace + " already exists");
                        return nsOp.update();
                    });

                    try {
                        base.evaluate();
                    } finally {
                        if (created.get()) {
                            System.err.println("Deleting namespace: " + namespace);
                            client.namespaces().resource(ns).delete();
                        }
                    }
                }
            }
        };
    }

    private static KubernetesClient newClient() {
        return new KubernetesClientBuilder().build();
    }

    public static void assumeKubernetes() {
        try (KubernetesClient client = newClient()) {
            client.pods().list();
        } catch (Exception e) {
            assumeNoException("kubernetes cluster not available", e);
        }
    }
}
