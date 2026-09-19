#!/usr/bin/env bash
# Shared helpers for the scripts in this directory. Source it, do not run it:
#   . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# Provides the repository root, .env loading, logging, a docker compose wrapper and health waits.
set -euo pipefail

usage() {
  cat <<'EOF_USAGE'
Usage: . scripts/lib.sh

Library of helpers for the other scripts (repository root, .env loading, logging,
docker compose wrapper, waiting for healthy services). It does nothing when run.
EOF_USAGE
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  usage
  exit 0
fi

REMIT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export REMIT_ROOT

# The three application services, in start order.
# shellcheck disable=SC2034 # used by the scripts that source this file
REMIT_APP_SERVICES=(intake-service routing-service api-service)

# Git Bash on Windows rewrites arguments that look like POSIX paths (/home/remit/inbound) before
# docker.exe sees them. The scripts only pass container paths to docker, so turn that off.
export MSYS_NO_PATHCONV=1

if [[ -t 2 && -z "${NO_COLOR:-}" ]]; then
  _c_reset=$'\033[0m' _c_red=$'\033[31m' _c_green=$'\033[32m' _c_yellow=$'\033[33m' _c_blue=$'\033[34m'
else
  _c_reset='' _c_red='' _c_green='' _c_yellow='' _c_blue=''
fi

log_info() { printf '%s[%s]%s %s\n' "${_c_blue}" "$(date +%H:%M:%S)" "${_c_reset}" "$*" >&2; }
log_ok() { printf '%s[%s] %s%s\n' "${_c_green}" "$(date +%H:%M:%S)" "$*" "${_c_reset}" >&2; }
log_warn() { printf '%s[%s] WARN %s%s\n' "${_c_yellow}" "$(date +%H:%M:%S)" "$*" "${_c_reset}" >&2; }
log_error() { printf '%s[%s] ERROR %s%s\n' "${_c_red}" "$(date +%H:%M:%S)" "$*" "${_c_reset}" >&2; }

die() {
  log_error "$*"
  exit 1
}

# require_cmd <command>...: exits with a clear message when a tool is missing.
require_cmd() {
  local cmd
  for cmd in "$@"; do
    command -v "${cmd}" >/dev/null 2>&1 || die "'${cmd}' is required but not installed"
  done
}

# load_env [file]: exports every variable of .env (default <repo>/.env) into the environment.
# Variables that are already set win, so REMIT_API_URL=... scripts/x.sh still overrides .env.
# Carriage returns are stripped so a .env saved with Windows line endings still works.
load_env() {
  local file="${1:-${REMIT_ROOT}/.env}" line key value
  [[ -f "${file}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ "${line}" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "${line}" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if [[ -z "${!key+set}" ]]; then
      export "${key}=${value}"
    fi
  done <"${file}"
}

# compose <args>: docker compose for this repository, whatever the current directory is.
compose() {
  docker compose --project-directory "${REMIT_ROOT}" -f "${REMIT_ROOT}/docker-compose.yml" "$@"
}

# service_state <service>: prints "<state> <health>" of the compose service's container
# (for example "running healthy", "exited " or "missing ").
service_state() {
  local line
  line="$(compose ps -a --format '{{.State}} {{.Health}}' "$1" 2>/dev/null | head -n 1)"
  printf '%s\n' "${line:-missing }"
}

# print_status [service...]: one line per service with its state and health.
print_status() {
  local svc
  for svc in "$@"; do
    printf '  %-16s %s\n' "${svc}" "$(service_state "${svc}")" >&2
  done
}

# wait_for_healthy <timeout seconds> <service>...: waits until every service is running and
# healthy (or has exited 0, for one-shot jobs such as kafka-init). Prints the status of each
# service when done; returns 1 on timeout or when a service exits with an error.
wait_for_healthy() {
  local timeout="$1" start svc state pending
  shift
  start="$(date +%s)"
  while :; do
    pending=()
    for svc in "$@"; do
      state="$(service_state "${svc}")"
      case "${state}" in
        "running healthy") ;;
        exited*)
          if [[ "$(compose ps -a --format '{{.ExitCode}}' "${svc}" | head -n 1)" != "0" ]]; then
            print_status "$@"
            log_error "${svc} exited with an error; see: docker compose logs ${svc}"
            return 1
          fi
          ;;
        *) pending+=("${svc}") ;;
      esac
    done
    if [[ ${#pending[@]} -eq 0 ]]; then
      print_status "$@"
      return 0
    fi
    if (($(date +%s) - start >= timeout)); then
      print_status "$@"
      log_error "timed out after ${timeout}s waiting for: ${pending[*]}"
      return 1
    fi
    sleep 2
  done
}

# now_ms: milliseconds since the epoch (GNU date; falls back to whole seconds).
now_ms() {
  local ms
  ms="$(date +%s%3N 2>/dev/null)"
  if [[ "${ms}" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "${ms}"
  else
    printf '%s000\n' "$(date +%s)"
  fi
}
