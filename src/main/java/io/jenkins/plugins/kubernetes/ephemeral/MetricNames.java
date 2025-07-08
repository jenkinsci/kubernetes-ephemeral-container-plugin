package io.jenkins.plugins.kubernetes.ephemeral;

class MetricNames {

    private static final String PREFIX = "kubernetes.cloud.containers.ephemeral";
    public static final String EPHEMERAL_CONTAINERS_CREATED = PREFIX + ".created";
    public static final String EPHEMERAL_CONTAINERS_CREATION_FAILED = PREFIX + ".creation.failed";
    public static final String EPHEMERAL_CONTAINERS_CREATION_RETRIED = PREFIX + ".creation.retried";
    public static final String EPHEMERAL_CONTAINERS_CREATION_DURATION = PREFIX + ".creation.duration";
    public static final String EPHEMERAL_CONTAINERS_CREATION_WAIT_DURATION = PREFIX + ".creation.wait.duration";
}
