# Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md).

## Code Style

Code style is enforced through standard Jenkins spotless rules.

```shell
# fix formatting violations
mvn spotless:apply
```

## Testing

* Prefer JUnit 5 tests for unit tests

```shell
# run unit tests
mvn test
```

```shell
# start jenkins server with plugin installed
mvn hpi:run
```

To connect to a local cluster, set a new Kubernetes Cloud:

* Set Jenkins Root URL in `System Config`. Use machine IP address, not `localhost` or `127.0.0.1`. e.g. `http://192.168.1.21:8080/`
* Configure "Direct Connection"
  
## Integration Tests

To run integration test locally you will need a Kubernetes cluster and [ktunnel][ktunnel]. The tests will
create a namespace for the test and delete it after.

```shell
# run IT tests
mvn verify
```

Alternatively, if running in CI mode [kind][kind] and [ktunnel][ktunnel] will be downloaded
and used. A new cluster will be provisioned for the test run, then deleted after.

Maven profiles are set up for the following environments:

- `mac-arm64`
- `linux-amd64`
- `win-amd64`

```shell
# download kind and ktunnel then run IT tests
CI=true mvn verify
```


[kind]: https://kind.sigs.k8s.io/
[ktunnel]: https://github.com/omrikiei/ktunnel