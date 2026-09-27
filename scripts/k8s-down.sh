#!/usr/bin/env bash
# Tears down the local Kubernetes deployment: deletes the kind cluster, or only the remit
# namespace (with its volumes) when the cluster should stay.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/k8s-down.sh [-h] [--keep-cluster]

  (default)       kind delete cluster --name remit: removes the cluster, every pod and volume
  --keep-cluster  kubectl delete namespace remit: removes the stack and its volumes (MySQL,
                  MongoDB, Kafka, SFTP data) but keeps the cluster and its loaded images, so
                  the next scripts/k8s-up.sh starts from empty databases in about a minute
  -h, --help      show this help

Environment:
  REMIT_KUBE_CONTEXT  kubectl context for --keep-cluster (default kind-remit)
EOF_USAGE
}

keep_cluster=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --keep-cluster) keep_cluster=true ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

start="$(date +%s)"
if [[ "${keep_cluster}" == true ]]; then
  require_cmd kubectl
  log_info "kubectl delete namespace ${REMIT_K8S_NAMESPACE}"
  kube delete namespace "${REMIT_K8S_NAMESPACE}" --ignore-not-found --wait=true --timeout 300s
  log_ok "namespace ${REMIT_K8S_NAMESPACE} deleted in $(($(date +%s) - start))s; the cluster is still running"
else
  require_cmd kind
  if ! kind get clusters 2>/dev/null | grep -qx "${REMIT_KIND_CLUSTER}"; then
    log_info "no kind cluster named ${REMIT_KIND_CLUSTER}; nothing to do"
    exit 0
  fi
  log_info "kind delete cluster --name ${REMIT_KIND_CLUSTER}"
  kind delete cluster --name "${REMIT_KIND_CLUSTER}"
  log_ok "cluster ${REMIT_KIND_CLUSTER} deleted in $(($(date +%s) - start))s"
fi
