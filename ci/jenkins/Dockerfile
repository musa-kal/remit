# Jenkins controller for the Remit pipeline: Jenkins LTS on JDK 17 plus the tools the Jenkinsfile
# calls (docker CLI and compose plugin, node 22 with newman, shellcheck, jq, curl) and the plugins
# from ci/plugins.txt. Configuration as Code (casc.yaml) sets up users, credentials, SonarQube and
# the remit job, so no setup wizard runs. Build context: ci/.
FROM jenkins/jenkins:lts-jdk17

USER root

# Base tools, and git that trusts any repository owner: /repo is bind mounted from the host and
# owned by another uid, which git refuses to read otherwise.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg jq xz-utils \
    && git config --system --add safe.directory '*' \
    && rm -rf /var/lib/apt/lists/*

# Docker CLI, compose and buildx plugins from Docker's Debian repository. There is no daemon in
# this image: DOCKER_HOST points at the dind service of ci/docker-compose.jenkins.yml.
RUN install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "${VERSION_CODENAME}") stable" \
      > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin docker-buildx-plugin \
    && rm -rf /var/lib/apt/lists/*

# ShellCheck pinned to the version the scripts are checked with locally (Debian ships an older one).
ARG SHELLCHECK_VERSION=0.11.0
RUN curl -fsSL "https://github.com/koalaman/shellcheck/releases/download/v${SHELLCHECK_VERSION}/shellcheck-v${SHELLCHECK_VERSION}.linux.x86_64.tar.xz" \
      | tar -xJ -C /tmp \
    && install -m 0755 "/tmp/shellcheck-v${SHELLCHECK_VERSION}/shellcheck" /usr/local/bin/shellcheck \
    && rm -rf "/tmp/shellcheck-v${SHELLCHECK_VERSION}" \
    && shellcheck --version

# Node.js 22 (latest 22.x release, checksum verified) with Newman and the htmlextra reporter.
RUN set -e; \
    base=https://nodejs.org/dist/latest-v22.x; \
    curl -fsSL "${base}/SHASUMS256.txt" -o /tmp/SHASUMS256.txt; \
    file="$(awk '/linux-x64\.tar\.xz$/ {print $2}' /tmp/SHASUMS256.txt)"; \
    curl -fsSL "${base}/${file}" -o "/tmp/${file}"; \
    (cd /tmp && grep " ${file}\$" SHASUMS256.txt | sha256sum -c -); \
    tar -xJf "/tmp/${file}" -C /usr/local --strip-components=1 --no-same-owner; \
    rm -f "/tmp/${file}" /tmp/SHASUMS256.txt; \
    npm install -g --no-fund --no-audit newman newman-reporter-htmlextra; \
    npm cache clean --force; \
    node --version; newman --version

USER jenkins

COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY --chown=jenkins:jenkins jenkins/casc.yaml /usr/share/jenkins/casc/jenkins.yaml
ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/casc/jenkins.yaml
