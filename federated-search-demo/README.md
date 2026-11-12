# Federated agent search: final demo and evidence (Nov 12, 2026)

Evidence for #8424 (federated AI agent search across Apicurio Registry instances), CNCF LFX
Mentorship 2026 Term 3.

## What was run

Everything below was run at one commit, `d4e30db175d4844abae12ad1862e8a5fc0321f4d`, the head of
`aclfe/apicurio-registry` branch `feat/8576-ui-on-credentials`. That branch stacks:

- #10492 (federated search), head `38aac5181`, rebased onto main `71b2e9c4e`
- the credentialed peer queries and the auth-enabled multi-instance test, branch
  `feat/8575-credentialed-peers`, head `8c0e10f9d`
- the UI work for #8576 and the docs page for #8580

The image is `apicurio/apicurio-registry:fed-demo-d4e30db17`, id
`sha256:57c776360ebb22003e04eb1043cb382642e6c87272e5189a8542425cb9e431d8`, built from that commit with:

    ./mvnw package -Pprod -DskipTests -pl distro/docker -am -DskipCommitIdPlugin=true
    docker build -f distro/docker/target/docker/Dockerfile.jvm -t apicurio/apicurio-registry:fed-demo-d4e30db17 distro/docker/target/docker

The legacy peer is `quay.io/apicurio/apicurio-registry:latest-release` (3.3.3), id
`sha256:c9cae4c90ce46538abf673c68eb591345f23bcc6c2aa6bfd47189adcf609d8f1`.

## Files

- `demo.sh`: starts a coordinating registry and five peers on one Docker network, registers six
  peers, and runs one federated search that shows every source outcome, then the hop refusal,
  the perSourceLimit check and the per-peer metrics. `./demo.sh clean` removes the containers.
- `demo-output.txt`: the output of `demo.sh` against the image above.
- `screenshots/`: the UI against the same demo registry.
- `logs/results.txt`: the commands and the per-class and per-test results of the runs at the same commit.

## Results

| Check | Command | Result |
|---|---|---|
| Checkstyle | `./mvnw checkstyle:check -pl app` | 0 violations |
| Targeted unit and REST tests | `./mvnw test -Dlocal -pl app -am -Dtest=...` (28 classes, full command in `logs/results.txt`) | 367 tests, 0 failures |
| Module boundary | `./mvnw test -Dlocal -pl app -am -Dtest=AgentsBoundaryTest` | 2 tests, 0 failures |
| Federation integration tests | `./mvnw verify -Pintegration-tests -Dgroups=federation -Dregistry3-image=apicurio/apicurio-registry:fed-demo-d4e30db17 -pl integration-tests -am` | 7 tests, 0 failures |
| UI | `cd ui/ui-app && npm test && npm run lint && npm run build` | 502 tests; 0 lint errors; built |

The first run of the unit tests stalled in the `beforeEach` of `FederatedSearchAuthTest`. An SDK
call to list the global rules never returned, although the server had answered it, and Quarkus
reported a hang after 10 minutes. The run was stopped. The same 28 classes then passed in a
rerun with nothing else running, and the hang did not come back. The full logs of both runs are kept locally and can be shared.
