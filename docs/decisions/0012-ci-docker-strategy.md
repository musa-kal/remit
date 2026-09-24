# ADR 0012: Docker access for the Jenkins pipeline

- Status: accepted
- Date: 2026-09-30

## Context

The Jenkins pipeline (spec section 14) needs a Docker daemon for four different things:

1. Testcontainers in `./mvnw verify` starts MySQL, Kafka, MongoDB and SFTP containers and connects to their mapped ports.
2. `scripts/build.sh` builds the three service images.
3. `scripts/deploy.sh compose` runs `docker-compose.yml`, which bind mounts files of the checkout (`./docker/mysql/init.sql`, `./docker/mysql/init-users.sh`, `./docker/mongo/init.js`, `./docker/kafka/create-topics.sh`).
4. `scripts/smoke-test.sh` and Newman call the API on the port the stack publishes.

The spec suggests mounting the host's Docker socket into the Jenkins container. On Docker Desktop for Windows (the machine this project is built on) that breaks item 3: the Jenkins workspace is `/var/jenkins_home/workspace/remit` inside the Jenkins container, but the daemon behind the socket resolves bind-mount sources on its own file system, where that path does not exist. Compose then mounts empty directories in place of the init scripts, and MySQL starts without its schemas. It also breaks items 1 and 4 in a smaller way: mapped ports are published on the host, which a container reaches as `host.docker.internal`, not `localhost`. The usual fix, a workspace on a host bind mount at an identical path, is not possible with a Windows host path such as `C:\Users\...`.

## Decision

`ci/docker-compose.jenkins.yml` runs a Docker-in-Docker sidecar (`docker:28-dind`, privileged) next to Jenkins:

- `dind` listens on plain TCP 2375 on the internal `remit-ci` network only (`DOCKER_TLS_CERTDIR=""`); the port is not published on the host.
- Jenkins has `DOCKER_HOST=tcp://dind:2375`, so the docker CLI, the compose plugin and Testcontainers all talk to dind.
- The Jenkins home volume is mounted into both containers at the same path, `/var/jenkins_home`. A bind mount of `/var/jenkins_home/workspace/remit/docker/mysql/init.sql` names the same file on the daemon as in the workspace.
- `TESTCONTAINERS_HOST_OVERRIDE=dind`: containers publish their ports on dind, so Testcontainers connects to `dind:<mapped port>`.
- The pipeline calls the deployed stack at `http://dind:8080` (`REMIT_API_URL` for the smoke test, `--base-url http://dind:8080/api/v1` for Newman).
- dind keeps its images and build cache in the `dind-data` volume, so later runs reuse the Maven and base image layers.

The repository reaches Jenkins as a read-only bind mount at `/repo` and the job clones `file:///repo` (branch `main`). The clone runs in the Jenkins container, which is the only place that path has to exist.

The daemon is pinned to the Engine 28 major (`docker:28-dind`), the version the pipeline was verified with. Engine 29 raised the minimum API version it accepts from clients, so an upgrade should be checked against the docker-java client inside Testcontainers first. The CLI in the Jenkins image (29.x) negotiates down to the daemon's API version.

## Consequences

- The dind container is privileged. That is acceptable for a local CI stack on a developer machine; a shared CI server would use rootless dind, Kubernetes agents or a remote builder instead.
- Images built by the pipeline live in dind, not in the host's Docker. A local `docker images` does not show them.
- The pipeline behaves the same on Linux, macOS and Windows hosts, because nothing depends on host paths or the host's Docker socket.
- Memory: dind runs the Testcontainers containers during the build and the whole remit stack during E2E, on top of Jenkins and SonarQube. See ci/README.md for the Docker memory this needs.
- A real deployment would point the job at `https://github.com/Musa-Kal/remit.git` with a credential instead of the `/repo` mount.
