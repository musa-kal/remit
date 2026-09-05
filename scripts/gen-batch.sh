#!/usr/bin/env bash
# Generates a valid payment batch file (spec 3.2 and 3.3) with a correct trailer, for load tests
# such as the 10,000-row NFR-05 run. Every row passes intake validation: rows rotate through EFT,
# WIRE and INTERAC, amounts stay inside the illustrative channel limits, and the control total is
# summed in integer cents so it never drifts.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/gen-batch.sh [-h] [-o DIR] <clientId> <rows>

Writes DIR/PAY_<clientId>_<yyyyMMddHHmmss>.csv (DIR defaults to the current directory) and
prints its path.

Arguments:
  clientId   3 to 10 upper-case letters or digits, for example ACME01
  rows       number of instruction rows, 1 or more

Options:
  -o DIR     output directory (created if missing)
  -h         show this help

Environment:
  VALUE_DATE  value date for every row (yyyy-MM-dd). Default: tomorrow in America/Toronto.

Example:
  scripts/gen-batch.sh -o /tmp ACME01 10000
EOF
}

die() {
  echo "gen-batch: $*" >&2
  exit 1
}

tomorrow_in_toronto() {
  # GNU date (Linux, Git Bash) first, then BSD date (macOS).
  TZ=America/Toronto date -d '+1 day' +%Y-%m-%d 2>/dev/null ||
    TZ=America/Toronto date -v+1d +%Y-%m-%d
}

out_dir="."
while getopts ":ho:" opt; do
  case "${opt}" in
    h)
      usage
      exit 0
      ;;
    o) out_dir="${OPTARG}" ;;
    :) die "option -${OPTARG} needs a value" ;;
    *) die "unknown option -${OPTARG} (see -h)" ;;
  esac
done
shift $((OPTIND - 1))

[[ $# -eq 2 ]] || {
  usage >&2
  exit 1
}
client_id="$1"
rows="$2"

[[ "${client_id}" =~ ^[A-Z0-9]{3,10}$ ]] || die "clientId must be 3 to 10 upper-case letters or digits"
[[ "${rows}" =~ ^[1-9][0-9]*$ ]] || die "rows must be a positive integer"

value_date="${VALUE_DATE:-$(tomorrow_in_toronto)}"
[[ "${value_date}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "VALUE_DATE must be yyyy-MM-dd"

stamp="$(TZ=America/Toronto date +%Y%m%d%H%M%S)"
mkdir -p "${out_dir}"
file="${out_dir}/PAY_${client_id}_${stamp}.csv"

awk -v rows="${rows}" -v value_date="${value_date}" -v run="G${stamp}" '
BEGIN {
  print "instruction_ref,debtor_name,debtor_institution,debtor_transit,debtor_account," \
        "creditor_name,creditor_institution,creditor_transit,creditor_account,amount," \
        "currency,channel,value_date,remittance_info"
  total = 0
  for (i = 1; i <= rows; i++) {
    kind = i % 3
    if (kind == 0) {
      # INTERAC: CAD only, at most 10,000.00. Here 1.00 to 9,000.99.
      channel = "INTERAC"; currency = "CAD"; cents = 100 + (i * 7919) % 900000
    } else if (kind == 1) {
      # EFT: at most 25,000,000.00. Here 10.00 to 50,009.99.
      channel = "EFT"; currency = (i % 2 ? "CAD" : "USD"); cents = 1000 + (i * 104729) % 5000000
    } else {
      # WIRE: no maximum; kept under 1,000,000.00 so no row needs review.
      channel = "WIRE"; currency = (i % 2 ? "USD" : "CAD"); cents = 100000 + (i * 1299709) % 50000000
    }
    total += cents
    printf "%s-%d,Generated Debtor %d,001,00011,%d,Generated Creditor %d,002,00022,%d,%d.%02d,%s,%s,%s,Load test %d\n", \
      run, i, i % 50, 1000000 + i % 8999999, i % 75, 2000000 + (i * 31) % 7999999, \
      int(cents / 100), cents % 100, currency, channel, value_date, i
  }
  # %.0f keeps large totals exact in awks whose %d is 32 bit (mawk).
  printf "TRAILER,%d,%.0f.%02d\n", rows, int(total / 100), total % 100
}' >"${file}"

echo "${file}"
