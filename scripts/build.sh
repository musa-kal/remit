#!/usr/bin/env bash
# Builds and tests the Maven reactor, then builds the three service images tagged with the git
# short SHA and latest.
set -euo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() {
  cat <<'EOF_USAGE'
Usage: scripts/build.sh [-h] [--skip-tests] [--no-cache] [--tag TAG]

1. ./mvnw -B verify (unit and integration tests, Spotless check, JaCoCo gate).
2. docker build of intake-service, routing-service and api-service from the repository root,
   each tagged remit/<service>:<git short SHA> and remit/<service>:latest.

Options:
  --skip-tests  skip step 1, for CI stages that already ran the tests (the images compile the
                code themselves, so the result is the same)
  --no-cache    build the images without the Docker layer cache
  --tag TAG     use TAG instead of the git short SHA (latest is always added)
  -h, --help    show this help

Compose runs remit/<service>:${REMIT_IMAGE_TAG:-latest}, so a plain docker compose up picks up
the images built here.
EOF_USAGE
}

skip_tests=false
no_cache=false
tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    --skip-tests) skip_tests=true ;;
    --no-cache) no_cache=true ;;
    --tag)
      [[ $# -ge 2 ]] || die "--tag needs a value"
      tag="$2"
      shift
      ;;
    *)
      usage >&2
      exit 1
      ;;
  esac
  shift
done

require_cmd docker git
cd "${REMIT_ROOT}"

if [[ -z "${tag}" ]]; then
  tag="$(git rev-parse --short HEAD)"
fi
[[ "${tag}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || die "invalid image tag '${tag}'"

if [[ "${skip_tests}" == true ]]; then
  log_warn "skipping ./mvnw -B verify (--skip-tests)"
else
  log_info "./mvnw -B verify"
  ./mvnw -B verify
fi

build_args=()
if [[ "${no_cache}" == true ]]; then
  build_args+=(--no-cache)
fi

for service in "${REMIT_APP_SERVICES[@]}"; do
  log_info "building remit/${service}:${tag}"
  docker build ${build_args[@]+"${build_args[@]}"} \
    -f "${service}/Dockerfile" \
    -t "remit/${service}:${tag}" \
    -t "remit/${service}:latest" \
    .
done

log_ok "images built:"
for service in "${REMIT_APP_SERVICES[@]}"; do
  printf '  remit/%s:%s  remit/%s:latest\n' "${service}" "${tag}" "${service}" >&2
done
