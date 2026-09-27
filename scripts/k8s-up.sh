#!/usr/bin/env bash
# Brings up the Remit stack on a local kind cluster: creates the cluster if needed, loads the
# service images, creates the Secret from .env, applies the local kustomize overlay and waits
# until the infrastructure, the kafka-init Job and the three services are ready. Safe to rerun.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/k8s-up.sh [-h] [--build] [--timeout SECONDS]

Steps (each one is skipped or a no-op when already done):
  1. kind create cluster --config k8s/kind-config.yaml (cluster "remit", context kind-remit)
  2. build the remit/<service>:latest images when missing (scripts/build.sh --skip-tests), then
     kind load docker-image them into the cluster; a service whose image changed is restarted
  3. apply k8s/base/namespace.yaml and the remit-secrets Secret (scripts/k8s-secrets.sh)
  4. kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/overlays/local | kubectl apply
     (the kafka-init Job is deleted first so it runs again)
  5. wait for MySQL, MongoDB, Kafka, SFTP, the kafka-init Job and the three deployments

Options:
  --build            rebuild the three images even when they exist
  --timeout SECONDS  how long to wait for each rollout and the Job (default 600)
  -h, --help         show this help

The operator API is then at http://localhost:8080 (node port 30080, mapped by the kind config),
the same address as docker compose, so the compose stack must not be running.

Requires docker, kind, kubectl and .env. Tear down with scripts/k8s-down.sh.
EOF_USAGE
}

build=false
timeout=600
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --build) build=true ;;
    --timeout)
      [[ $# -ge 2 && "$2" =~ ^[0-9]+$ ]] || die "--timeout needs a number of seconds"
      timeout="$2"
      shift
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

require_cmd docker kind kubectl
[[ -f "${REMIT_ROOT}/.env" ]] || die ".env is missing: cp .env.example .env and fill in the secrets"
load_env

started="$(date +%s)"
step_started="${started}"
step() {
  step_started="$(date +%s)"
  log_info "== $*"
}
step_done() {
  log_ok "$* ($(($(date +%s) - step_started))s)"
}

# ---------------------------------------------------------------------------------------------
# 1. Cluster
# ---------------------------------------------------------------------------------------------

step "kind cluster ${REMIT_KIND_CLUSTER}"
if kind get clusters 2>/dev/null | grep -qx "${REMIT_KIND_CLUSTER}"; then
  log_info "cluster ${REMIT_KIND_CLUSTER} exists"
else
  if [[ "$(service_state api-service)" == running* ]]; then
    die "the compose api-service holds localhost:8080, which the cluster needs; run docker compose down first"
  fi
  kind create cluster --config "${REMIT_ROOT}/k8s/kind-config.yaml" --wait 120s
fi
kube get nodes >/dev/null || die "cannot reach the cluster through context kind-${REMIT_KIND_CLUSTER}"
step_done "cluster ready"

# ---------------------------------------------------------------------------------------------
# 2. Images
# ---------------------------------------------------------------------------------------------

step "service images"
need_build="${build}"
for svc in "${REMIT_APP_SERVICES[@]}"; do
  if ! docker image inspect "remit/${svc}:latest" >/dev/null 2>&1; then
    log_info "remit/${svc}:latest does not exist yet"
    need_build=true
  fi
done
if [[ "${need_build}" == true ]]; then
  "${REMIT_ROOT}/scripts/build.sh" --skip-tests
fi

# kind cannot tell whether an image is already on the node when Docker Desktop uses the
# containerd image store (the IDs differ), so it would copy all three again on every run. The
# Docker image ID of each loaded image is therefore recorded as an annotation on the node: an
# unchanged ID skips the copy, a new one is loaded, and its service is restarted below because
# its pods still run the old image under the same tag.
node="${REMIT_KIND_CLUSTER}-control-plane"
changed=()
for svc in "${REMIT_APP_SERVICES[@]}"; do
  image_id="$(docker image inspect --format '{{.Id}}' "remit/${svc}:latest")"
  annotation="remit.local/image-${svc}"
  loaded_id="$(kube get node "${node}" -o jsonpath="{.metadata.annotations.remit\.local/image-${svc}}" 2>/dev/null || true)"
  if [[ "${loaded_id}" == "${image_id}" ]]; then
    log_info "remit/${svc}:latest (${image_id:7:12}) is already on the node"
    continue
  fi
  load_started="$(date +%s)"
  output="$(kind load docker-image --name "${REMIT_KIND_CLUSTER}" "remit/${svc}:latest" 2>&1)" ||
    die "kind load docker-image remit/${svc}:latest failed: ${output}"
  kube annotate node "${node}" --overwrite "${annotation}=${image_id}" >/dev/null
  changed+=("${svc}")
  log_info "loaded remit/${svc}:latest (${image_id:7:12}) in $(($(date +%s) - load_started))s"
done
step_done "images loaded"

# ---------------------------------------------------------------------------------------------
# 3 and 4. Namespace, Secret, overlay
# ---------------------------------------------------------------------------------------------

step "namespace, secret and overlay"
kube apply -f - <"${REMIT_ROOT}/k8s/base/namespace.yaml"
"${REMIT_ROOT}/scripts/k8s-secrets.sh"

existing=()
for svc in "${changed[@]+"${changed[@]}"}"; do
  if kube get deployment "${svc}" >/dev/null 2>&1; then
    existing+=("${svc}")
  fi
done

# A Job's pod template cannot be changed in place, and the topic script is safe to rerun.
kube delete job kafka-init --ignore-not-found >/dev/null
(cd "${REMIT_ROOT}" && kubectl kustomize --load-restrictor LoadRestrictionsNone k8s/overlays/local) |
  kube apply -f -

for svc in "${existing[@]+"${existing[@]}"}"; do
  log_info "restarting ${svc} to run its new image"
  kube rollout restart "deployment/${svc}"
done
step_done "overlay applied"

# ---------------------------------------------------------------------------------------------
# 5. Wait
# ---------------------------------------------------------------------------------------------

# wait_for_job <name>: waits until the Job has succeeded; shows its log and fails when it failed.
wait_for_job() {
  local job="$1" deadline succeeded failed
  deadline=$(($(date +%s) + timeout))
  while :; do
    succeeded="$(kube get job "${job}" -o jsonpath='{.status.succeeded}' 2>/dev/null || true)"
    [[ "${succeeded}" == 1 ]] && return 0
    failed="$(kube get job "${job}" -o jsonpath='{.status.conditions[?(@.type=="Failed")].status}' 2>/dev/null || true)"
    if [[ "${failed}" == True ]]; then
      kube logs "job/${job}" --tail 30 >&2 || true
      die "job ${job} failed"
    fi
    if (($(date +%s) >= deadline)); then
      kube logs "job/${job}" --tail 30 >&2 || true
      die "timed out after ${timeout}s waiting for job ${job}"
    fi
    sleep 3
  done
}

step "infrastructure"
for sts in mysql mongo kafka; do
  kube rollout status "statefulset/${sts}" --timeout "${timeout}s"
done
kube rollout status deployment/sftp --timeout "${timeout}s"
wait_for_job kafka-init
step_done "infrastructure ready, topics created"

step "services"
for svc in "${REMIT_APP_SERVICES[@]}"; do
  kube rollout status "deployment/${svc}" --timeout "${timeout}s"
done
step_done "services ready"

kube get pods -o wide >&2
log_ok "Remit is up on kind in $(($(date +%s) - started))s"
log_info "API:        http://localhost:8080 (Swagger UI: /swagger-ui.html)"
log_info "smoke test: scripts/smoke-test.sh --target k8s"
log_info "logs:       scripts/logs.sh routing-service k8s"
log_info "tear down:  scripts/k8s-down.sh"
