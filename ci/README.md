# Local CI: Jenkins and SonarQube

`ci/docker-compose.jenkins.yml` runs the primary CI of Remit on one machine:

| Service | Image | Host port | Purpose |
|---|---|---|---|
| `jenkins` | `remit-ci/jenkins` (built from `ci/jenkins/Dockerfile`) | 8090 | Jenkins LTS on JDK 17, configured as code, runs the `remit` job |
| `dind` | `docker:28-dind` | none | Docker daemon for the pipeline (Testcontainers, image builds, the compose stack) |
| `sonarqube` | `sonarqube:community` | 9000 | SonarQube Community Build, quality gate `remit` |
| `sonar-db` | `postgres:16-alpine` | none | SonarQube's database |

Every service has a healthcheck, and all data lives in named volumes (`remit-ci_jenkins-home`, `remit-ci_dind-data`, `remit-ci_sonarqube-*`, `remit-ci_sonar-db-data`). The GitHub Actions workflow (`.github/workflows/ci.yml`) mirrors only the static checks and the build; this Jenkins pipeline is the complete one.

## Prerequisites

- Docker with the compose plugin, bash, curl and jq (Git Bash works on Windows).
- Memory: give Docker at least 10 GB. Measured during a green run, the four CI containers together peaked at 7.7 GiB in the E2E and API test stages: SonarQube about 2.7 GiB, Jenkins (controller plus Maven) about 1.6 GiB, and dind 3.4 GiB with the whole remit stack running inside it. That does not fit the Docker Desktop default on a 16 GB machine (half the RAM, 7.5 GiB for the whole VM), so the verified setup raised the limit. With the WSL 2 backend it is set in `%USERPROFILE%\.wslconfig`:

  ```ini
  [wsl2]
  memory=12GB
  ```

  followed by `wsl --shutdown` and a Docker Desktop restart (`docker info` then reports about 11.7 GiB). With the Hyper-V backend it is the memory slider in Docker Desktop's settings.
- Free host ports 8090 and 9000. The remit stack runs inside dind during CI and publishes nothing on the host, so a local remit stack on 8080 to 8082 can run at the same time. The optional Kafka UI of the remit compose file also defaults to 8090; start it with another `KAFKA_UI_PORT` while Jenkins runs, or set `JENKINS_PORT` in `ci/.env`.

## One-time setup

```bash
ci/setup.sh
```

The script is idempotent; rerun it whenever something looks off. It:

1. creates `ci/.env` from `ci/.env.example` and replaces every `change-me` value with a random one (Jenkins admin password, SonarQube admin and database passwords, and the secrets of the remit stack the pipeline deploys). `ci/.env` is gitignored.
2. starts `sonar-db` and `sonarqube` and waits for `STATUS=UP`.
3. changes SonarQube's default `admin/admin` password to `SONAR_ADMIN_PASSWORD`.
4. switches SonarQube to the Standard Experience, whose blocker and critical severities are the terms the spec uses.
5. creates the project `ca.musakaleem.remit:remit` and the quality gate `remit`, sets it as the default gate and assigns it to the project (see [Quality gate](#quality-gate)).
6. creates the webhook `jenkins` pointing at `http://jenkins:8080/sonarqube-webhook/`, which `waitForQualityGate` needs.
7. generates the user token `jenkins` and writes it to `SONAR_TOKEN` in `ci/.env` (a valid token is kept).
8. builds the Jenkins image and starts `dind` and `jenkins`. On a new token it recreates the Jenkins container so Configuration as Code picks it up.

Logins:

- Jenkins: <http://localhost:8090>, user `admin`, password `JENKINS_ADMIN_PASSWORD` from `ci/.env`.
- SonarQube: <http://localhost:9000>, user `admin`, password `SONAR_ADMIN_PASSWORD` from `ci/.env`.

## Run the pipeline

Commit first: the job builds what is committed on `main`. The repository is bind mounted read only at `/repo` in the Jenkins container and the job clones `file:///repo` (a real deployment would use `https://github.com/Musa-Kal/remit.git` with a credential). Then open <http://localhost:8090/job/remit/> and click **Build Now**. The first green run took 16 minutes, most of it `./mvnw verify` (4.4 minutes) and the three image builds (8.7 minutes, which download the Maven dependencies into the dind build cache once); see the README for the stage timings.

From a shell (`JENKINS_ADMIN_PASSWORD` from `ci/.env`):

```bash
jar=$(mktemp)
crumb=$(curl -s -c "$jar" -u "admin:$JENKINS_ADMIN_PASSWORD" http://localhost:8090/crumbIssuer/api/json | jq -r '.crumbRequestField + ":" + .crumb')
curl -s -b "$jar" -u "admin:$JENKINS_ADMIN_PASSWORD" -H "$crumb" -X POST http://localhost:8090/job/remit/build
```

## Pipeline stages

| # | Stage | What runs |
|---|---|---|
| 1 | Checkout | `checkout scm` |
| 2 | Static checks | `./mvnw -B spotless:check`, `shellcheck scripts/*.sh ci/*.sh` |
| 3 | Build and test | `./mvnw -B verify` (unit and Testcontainers integration tests, Spotless, JaCoCo gate); publishes JUnit results and JaCoCo coverage |
| 4 | SonarQube analysis | `withSonarQubeEnv('sonarqube') { ./mvnw -B sonar:sonar }` |
| 5 | Quality gate | `timeout(5 min) { waitForQualityGate abortPipeline: true }` |
| 6 | Docker build | `scripts/build.sh --skip-tests` (the tests ran in stage 3) |
| 7 | E2E | writes `.env` from the credentials, `scripts/deploy.sh compose`, `scripts/smoke-test.sh --no-up` |
| 8 | API tests | `scripts/newman.sh -e docker --base-url http://dind:8080/api/v1` (cli, junit and htmlextra reporters); publishes `newman/junit.xml` and the HTML report |
| post | always | saves `docker compose ps` and logs, `docker compose down -v`, archives `newman/**`, `**/target/site/jacoco/**` and the compose logs; removes `.env` |

Stage order: the spec lists API tests before E2E. Here E2E runs first because the Postman collection asserts the state `scripts/smoke-test.sh` leaves behind (the five samples plus one dead-lettered `FORCE_FAIL` instruction, see `docs/e2e-expectations.md`). Both stages exist and use the tools the spec names; the stack is deployed once and torn down in `post`.

## Credentials

Configuration as Code (`ci/jenkins/casc.yaml`) creates them from `ci/.env`. Compose hands the values to Jenkins as secret files in `/run/secrets`, so they are not in the controller's environment and builds only see them through `credentials()` bindings, masked in the log.

| Credential id | Kind | Source in `ci/.env` | Used for |
|---|---|---|---|
| `sonar-token` | secret text | `SONAR_TOKEN` | SonarQube server `sonarqube` |
| `remit-operator-password` | secret text | `REMIT_OPERATOR_PASSWORD` | seeded operator user (smoke test, Newman) |
| `remit-admin-password` | secret text | `REMIT_ADMIN_PASSWORD` | seeded admin user (smoke test, Newman) |
| `remit-jwt-secret` | secret text | `REMIT_JWT_SECRET` | api-service signing key |
| `remit-mysql-root-password` | secret text | `MYSQL_ROOT_PASSWORD` | compose stack |
| `remit-intake-db-password` | secret text | `REMIT_INTAKE_DB_PASSWORD` | compose stack |
| `remit-routing-db-password` | secret text | `REMIT_ROUTING_DB_PASSWORD` | compose stack |
| `remit-api-db-password` | secret text | `REMIT_API_DB_PASSWORD` | compose stack |
| `remit-mongo-root-password` | secret text | `MONGO_ROOT_PASSWORD` | compose stack |
| `remit-mongo-password` | secret text | `REMIT_MONGO_PASSWORD` | compose stack |
| `remit-sftp-password` | secret text | `REMIT_SFTP_PASSWORD` | compose stack |

The E2E stage writes the workspace `.env` from these bindings (non-secret values come from `.env.example`) with shell tracing off, and `post` deletes it.

## Quality gate

`ci/setup.sh` copies "Sonar way" into a gate named `remit` and adds the spec's conditions:

| Metric | Fails when | Scope |
|---|---|---|
| Coverage | below 80% | new code and overall |
| Blocker issues | above 0 | new code and overall |
| Critical issues | above 0 | new code and overall |
| Vulnerabilities | above 0 | new code and overall |
| Duplicated lines | above 2.9%, so the value must stay below the spec's 3% (SonarQube shows thresholds with one decimal, so 2.99 would display as 3.0) | new code and overall |
| Issues (Sonar way) | above 0 | new code |
| Security hotspots reviewed (Sonar way) | below 100% | new code |

The new code definition is SonarQube's default, "previous version". On the first analysis of a project there is no new code, so SonarQube does not evaluate new-code conditions; the overall conditions make the first gate meaningful and keep it that way, and every later analysis also checks the lines changed since the first one. SonarQube ignores the coverage and duplication conditions on new code when fewer than 20 lines are new.

## Docker strategy

Jenkins does not use the host's Docker socket. A `docker:28-dind` sidecar is the daemon (`DOCKER_HOST=tcp://dind:2375`, plain TCP on the internal network), and the Jenkins home volume is mounted into both containers at `/var/jenkins_home`, so compose bind mounts of workspace files (`./docker/mysql/init.sql`) resolve on the daemon. Containers publish their ports on dind: Testcontainers uses `TESTCONTAINERS_HOST_OVERRIDE=dind`, the smoke test `REMIT_API_URL=http://dind:8080`, and Newman `--base-url http://dind:8080/api/v1`. Why the socket mount does not work on Docker Desktop for Windows: [ADR 0012](../docs/decisions/0012-ci-docker-strategy.md).

## Reports

- Build page: **Test Result** (JUnit from surefire, failsafe and Newman), **Coverage Report** (JaCoCo), **Newman API tests** (htmlextra report; Jenkins starts with a relaxed Content Security Policy so its scripts run).
- Build artifacts: `newman/`, every module's `target/site/jacoco/`, `compose-ps.txt` and `compose-logs.txt`.
- SonarQube: <http://localhost:9000/dashboard?id=ca.musakaleem.remit:remit>.

## Stop and tear down

```bash
docker compose -f ci/docker-compose.jenkins.yml down      # stop, keep Jenkins, SonarQube and the dind cache
docker compose -f ci/docker-compose.jenkins.yml down -v   # also delete every volume
```

After `down -v`, delete `ci/.env` as well (its SonarQube token and admin password no longer match) and run `ci/setup.sh` again.

## Troubleshooting

- `ci/setup.sh` cannot log in to SonarQube: the SonarQube volume was kept but `ci/.env` was recreated. Remove the volumes (`down -v`) or restore the old `SONAR_ADMIN_PASSWORD`.
- Quality gate stage waits until the timeout: check the webhook delivery under SonarQube **Administration > Configuration > Webhooks**.
- Plugin changes: edit `ci/plugins.txt` and rerun `ci/setup.sh` (it rebuilds the image).
- Configuration changes: edit `ci/jenkins/casc.yaml`, then `docker compose -f ci/docker-compose.jenkins.yml --env-file ci/.env up -d --build jenkins`, or rerun `ci/setup.sh`.
