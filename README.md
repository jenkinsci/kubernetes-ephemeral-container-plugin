# Kubernetes Ephemeral Container Plugin

[![kubernetes-ephemeral-container version](https://img.shields.io/jenkins/plugin/v/kubernetes-ephemeral-container.svg?label=kubernetes-ephemeral-container)](https://plugins.jenkins.io/kubernetes-ephemeral-container)
[![kubernetes-ephemeral-container installs](https://img.shields.io/jenkins/plugin/i/kubernetes-ephemeral-container.svg)](https://plugins.jenkins.io/kubernetes-ephemeral-container)
[![kubernetes-ephemeral-container license](https://img.shields.io/github/license/jenkinsci/kubernetes-ephemeral-container-plugin)](https://github.com/jenkinsci/kubernetes-ephemeral-container-plugin/blob/master/LICENSE)

[Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/) extension that adds the ability
to run steps in an [Ephemeral Container](https://kubernetes.io/docs/concepts/workloads/pods/ephemeral-containers). This plugin provides similar behavior to
[withDockerContainer](https://www.jenkins.io/doc/pipeline/steps/docker-workflow/#withdockercontainer-run-build-steps-inside-a-docker-container)
but for Kubernetes. Many modern Kubernetes cluster environments do not have access to
the Docker daemon required by the [Docker plugin](https://plugins.jenkins.io/docker-workflow). This plugin aims to bridge
that gap.

## Table of Contents

- [Getting Started](#getting-started)
- [Limitations](#limitations)
- [Container Entrypoints](#container-entrypoints)
- [Environment Variables](#environment-variables)
- [Nested Ephemeral Container Steps](#nested-ephemeral-container-steps)
- [Step Rules](#step-rules)
- [Advanced Settings](#advanced-settings)
- [Metrics](#metrics)
- [Contributing](#contributing)
- [License](#license)

## Getting Started

The plugin adds an Ephemeral Container to the current Kubernetes Cloud agent Pod, executes some steps then
the container is terminated at the end of the block. This means pipelines may run step inside a container
not explicitly defined in the cloud Pod Template.

```groovy
pipeline {
    agent {
        // Reference a kubernetes pod template label
        label 'k8s-agent'
        // Use inline kubernetes agent 
        // kubernetes { ... }
    }
    stages {
        stage('build') {
            steps {
                withEphemeralContainer(image: 'maven:3') {
                    // Shares the build workspace
                    sh 'mvn clean install'
                }
                // Ephemeral container is terminated here
            }
        }
    }
}
```

## Limitations

The Ephemeral Container spec does not allow for specifying resources, but will still be subject to the
[Pod overall resource limits](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/). 
The kubelet may evict a Pod if an ephemeral container causes the Pod to exceed its resource
allocation. **To avoid eviction it is advisable to increase the limits of the primary container to account for
potential ephemeral container resource requirements.**

In Kubernetes, the size limit for objects stored in etcd, including pod specs, is 1.5MB per key-value pair.
This means there is a finite number of ephemeral containers that may be added to a build agent. This is usually
not much of an issue for most pipelines, but could become an issue for long-running Pods. **For this reason
it is not recommended to re-use agents node for multiple builds. Setting the Pod Template idle minutes property
("Time in minutes to retain agent when idle") to `0` is a good practice.**

- Windows containers are not supported
- Container must have a shell available

## Best Practices

- Set the Pod Template idle minutes property ("Time in minutes to retain agent when idle") to `0`. This prevents Pod
  spec container accumulation across builds which could lead to Pod spec size limits.
- Do not loop over `withEphemeralContainer`
  ```groovy
  // Bad - Could trigger Pod spec size limits
  for (def file in files) {
    withEphemeralContainer("koalaman/shellcheck") {
      sh "shellcheck ${file}"
    }
  }
  
  // Better - Move the loop inside the container block
  withEphemeralContainer("koalaman/shellcheck") {
    for (def file in files) {
      sh "shellcheck ${file}"
    }
  }
  
  // Best - Execute a single shell command in the container
  withEphemeralContainer("koalaman/shellcheck") {
    sh "shellcheck ${files.join(' ')}"
  }
  ```

## Container Entrypoints

For containers to work properly the container entrypoint, if defined, must be able to accept and execute
a monitor command provided by this plugin. By default, the monitor command will be passed as container command
args.

```bash
#!/bin/bash
# /opt/docker/entrypoint.sh

# do some custom entrypoint stuff

# execute monitor command provided by jenkins
exec "$@"
```

If the container has an entrypoint that does not respect command args, the entrypoint can be disabled by passing
an empty command line.

```groovy
// disable container entrypoint
withEphemeralContainer(image: image, command: []) {
    sh 'echo "hello"'
}
```

## Environment Variables

When ephemeral containers are enabled for a cloud a build environment variable will be added. This is useful for 
script library authors that may need to provide alternate execution options, for example fallback 
to [withDockerContainer](https://www.jenkins.io/doc/pipeline/steps/docker-workflow/#withdockercontainer-run-build-steps-inside-a-docker-container).

* `KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED=true`

```groovy
// vars/withMaven.groovy
def call(config = [:], body = null) {
    def image = "maven:${config.image?:'3'}"
    if (env.KUBERNETES_CLOUD_EPHEMERAL_CONTAINERS_ENABLED == "true") {
        withEphemeralContainer(image: image) {
            body()
        }
    } else {
        withDockerContainer(image: image) {
            body()
        }
    }
}
```

Container environment variables may also be configured. This may be necessary for the container entrypoint, but 
otherwise work the same as `withEnv`.

```groovy
withEphemeralContainer(image: 'maven', envVars: [envVar(key: 'FOO', value: 'bar')]) {
    withEnv(["BAR=baz"]) {
        sh 'echo "$FOO $BAR"'
    }
}
```

## Nested Ephemeral Container Steps

`withEphemeralContainer` steps may be nested. This does not mean one container runs inside another.
It just means that multiple running ephemeral containers are added to the agent Pod.

- All the containers share the same network, see [Pod Networking](https://kubernetes.io/docs/concepts/workloads/pods/#pod-networking). 
  This enables use cases where one container starts a service (i.e. http server) and another container calls it via 
  `localhost` as a pair of nested steps.
- If the `user` arg is set, the next nested container will inherit the `uid:gid` of the parent.

```groovy
withEphemeralContainer(image: 'redis:8-alpine') {
  timeout(time: 5, unit: 'SECONDS') {
    sh '''
       set -e
       redis-server &
       while ! nc -z localhost 6379; do sleep 1; done
    '''
  }
  withEphemeralContainer(image: 'redis:8-alpine') {
    sh 'redis-cli ping'
  }
}
```

## Step Rules

Ephemeral container step rules can be defined globally or by cloud.

### Container Image Rules

Use wildcard patterns to with either allow or reject specific images or registries that can be run.

- Use `*` to match any character sequence (i.e. `docker.io/library/*`)
- Image names are normalized (i.e. `maven` &rarr; `docker.io/library/maven`)
- If `Allow`, non-matches throw abort exception
- If `Reject`, matches throw abort exception

## Advanced Settings

For clusters with high load or pipelines with lots of concurrency the following system property settings may be tweaked
to improve container creation reliability.

| Property                                                                                        | Default | Description                                                                                                                         |
|-------------------------------------------------------------------------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------|
| `io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepExecution.startMaxRetry`         | `3`     | Number of retry attempts if container fails to start due to context deadline miss. May be increased for clusters with kubelet load. |
| `io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepExecution.startRetryMaxWaitSecs` | `2`     | Max wait time between retries. Used to spread kubelet load.                                                                         |
| `io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepExecution.pathMaxRetry`          | `10`    | Number of retry attempts to patch Pod spec. May be increased for highly concurrent pipelines create a lot patch conflicts.          |
| `io.jenkins.plugins.kubernetes.ephemeral.EphemeralContainerStepExecution.patchRetryMaxWaitSecs` | `2`     | Max wait time between retries. Used to minimize patch conflicts.                                                                    |

## Metrics

Container metrics are available via the [metrics](https://plugins.jenkins.io/metrics/) plugin endpoint. Metric [keys](src/main/java/io/jenkins/plugins/kubernetes/ephemeral/MetricNames.java)
start with `kubernetes.cloud.containers.ephemeral.`.

> $JENKINS_URL/metrics/currentUser/metrics?pretty=true

## Contributing

Refer to our [contribution guidelines](CONTRIBUTING.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE.md) file for details.